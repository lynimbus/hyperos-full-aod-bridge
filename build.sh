#!/usr/bin/env bash
# 从原厂 services.jar 生成打好补丁的 services.jar 和 Magisk/KernelSU 刷入包。
#
#   ./build.sh /path/to/stock/services.jar
#
# 需要网络（首次运行会把 JDK17 / smali / r8 下到 rom-patch/.tools）。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$HERE/.tools"
OUT="$HERE/out"
WORK="$HERE/.work"

STOCK="${1:-}"
[ -f "$STOCK" ] || { echo "用法: $0 <原厂 services.jar>"; exit 1; }

SMALI_VER=3.0.9
R8_VER=9.1.31
GUAVA_VER=31.1-android
# services.jar 的 dex 版本为 039，对应 smali 的 -a 29
DEX_API=29
# 反汇编按目标系统 API（Android 16）
SRC_API=36

fetch() { # url
  local f="$TOOLS/$(basename "$1")"
  [ -f "$f" ] || { echo "下载 $(basename "$1")"; curl -sSL -o "$f" "$1"; }
}

mkdir -p "$TOOLS" "$OUT" "$WORK"

if [ ! -x "$TOOLS"/jdk-17*/bin/javac ]; then
  # 截断的 JDK（无 bin/）会让后续 javac 在诡异处失败；直接清掉重下。
  rm -rf "$TOOLS"/jdk-17* "$TOOLS/jdk17.tar.gz"
  echo "下载 JDK 17"
  curl -sSL -o "$TOOLS/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$TOOLS/jdk17.tar.gz" -C "$TOOLS"
  rm -f "$TOOLS/jdk17.tar.gz"
fi
export JAVA_HOME="$(echo "$TOOLS"/jdk-17*)"
export PATH="$JAVA_HOME/bin:$PATH"

for a in smali-baksmali smali smali-dexlib2 smali-util; do
  fetch "https://maven.google.com/com/android/tools/smali/$a/$SMALI_VER/$a-$SMALI_VER.jar"
done
for url in \
  "https://maven.google.com/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar" \
  "https://repo1.maven.org/maven2/com/google/guava/guava/$GUAVA_VER/guava-$GUAVA_VER.jar" \
  "https://repo1.maven.org/maven2/com/beust/jcommander/1.64/jcommander-1.64.jar" \
  "https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar" \
  "https://repo1.maven.org/maven2/org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar" \
  "https://repo1.maven.org/maven2/org/antlr/antlr/3.5.2/antlr-3.5.2.jar" \
  "https://repo1.maven.org/maven2/org/antlr/stringtemplate/3.2.1/stringtemplate-3.2.1.jar"
do
  fetch "$url"
done

CP="$(ls "$TOOLS"/*.jar | tr '\n' ':')"
baksmali() { java -cp "$CP" com.android.tools.smali.baksmali.Main "$@"; }
smali()    { java -cp "$CP" com.android.tools.smali.smali.Main "$@"; }
d8()       { java -cp "$TOOLS/r8-$R8_VER.jar" com.android.tools.r8.D8 "$@"; }

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/bridge"

echo "== 1/5 编译 AodDozeBridge =="
# -XDstringConcat=inline 是必须的：javac 17 默认把 "a" + x 编成 invoke-dynamic →
# StringConcatFactory.makeConcatWithConstants，而 boot classpath 上没有那套 MethodHandle
# 机制，ART 解析 bootstrap method 时会直接 abort（原厂 4 个 dex 里 StringConcatFactory
# 出现 0 次，AOSP 框架也是这么编的）。改回 StringBuilder。
javac -nowarn -XDstringConcat=inline -d "$WORK/classes" \
  $(find "$HERE/stubs" "$HERE/src" -name '*.java')
d8 --release --min-api "$SRC_API" --no-desugaring \
  --output "$WORK/dex" "$WORK/classes/com/android/server/display/AodDozeBridge.class"
baksmali disassemble -a "$SRC_API" -o "$WORK/bridge" "$WORK/dex/classes.dex"

echo "== 2/5 拆出所有 classes*.dex =="
python3 - "$STOCK" "$WORK/dex_names" <<'PY'
import os, re, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = sorted(n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n))
open(sys.argv[2], 'w').write('\n'.join(names) + '\n')
for n in names:
    open(os.path.join(os.path.dirname(sys.argv[2]), n), 'wb').write(z.read(n))
PY

# 目标类位置在反汇编后确认（步骤 3）。

echo "== 3/5 反汇编并注入 =="
for DEX in $(cat "$WORK/dex_names"); do
  IDX="${DEX#classes}"; IDX="${IDX%.dex}"
  baksmali disassemble -a "$SRC_API" -o "$WORK/smali$IDX" "$WORK/$DEX"
done
LDA_DIR=""
for DIR in "$WORK"/smali*; do
  [ -f "$DIR/com/android/server/display/LocalDisplayAdapter\$LocalDisplayDevice\$1.smali" ] && LDA_DIR="$DIR"
done
[ -n "$LDA_DIR" ] || { echo "找不到 LocalDisplayAdapter\$LocalDisplayDevice\$1，请确认 services.jar 版本"; exit 1; }
echo "目标类位于 $LDA_DIR"
for DIR in "$WORK"/smali*; do
  python3 "$HERE/patch_smali.py" "$DIR" \
    "$WORK/bridge/com/android/server/display/AodDozeBridge.smali"
done

echo "== 4/5 回编译（仅被修改过的树） =="
REPLACE_ARGS=()
for DIR in "$WORK"/smali*; do
  [ -f "$DIR/.aod_patched" ] || continue
  IDX="$(basename "$DIR" | sed 's/^smali//')"
  smali assemble -a "$DEX_API" -o "$WORK/classes$IDX-patched.dex" "$DIR"
  REPLACE_ARGS+=("classes$IDX.dex=$WORK/classes$IDX-patched.dex")
done

echo "== 5/5 重新打包 =="
python3 "$HERE/repack_jar.py" "$STOCK" "$OUT/services.jar" "${REPLACE_ARGS[@]}"

MODDIR="$WORK/module"
rm -rf "$MODDIR"; mkdir -p "$MODDIR/system/framework"
cp "$HERE/magisk/module.prop" "$MODDIR/"
cp "$HERE/magisk/customize.sh" "$MODDIR/customize.sh"
cp "$OUT/services.jar" "$MODDIR/system/framework/services.jar"
python3 - "$MODDIR" "$OUT/aod-doze-bridge-rom.zip" <<'PY'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
PY
echo
echo "输出:"
echo "  $OUT/services.jar"
echo "  $OUT/aod-doze-bridge-rom.zip"
