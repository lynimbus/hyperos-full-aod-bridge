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

if [ ! -d "$TOOLS"/jdk-17* ]; then
  echo "下载 JDK 17"
  curl -sSL -o "$TOOLS/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$TOOLS/jdk17.tar.gz" -C "$TOOLS"
fi
export JAVA_HOME="$(echo "$TOOLS"/jdk-17*)"
export PATH="$JAVA_HOME/bin:$PATH"

for a in smali-baksmali smali smali-dexlib2 smali-util; do
  fetch "https://maven.google.com/com/android/tools/smali/$a/$SMALI_VER/$a-$SMALI_VER.jar"
done
fetch "https://maven.google.com/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar"
fetch "https://repo1.maven.org/maven2/com/google/guava/guava/$GUAVA_VER/guava-$GUAVA_VER.jar"
fetch "https://repo1.maven.org/maven2/com/beust/jcommander/1.64/jcommander-1.64.jar"
fetch "https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar"
fetch "https://repo1.maven.org/maven2/org/antlr/antlr-runtime/3.5.2/antlr-runtime-3.5.2.jar"
fetch "https://repo1.maven.org/maven2/org/antlr/antlr/3.5.2/antlr-3.5.2.jar"
fetch "https://repo1.maven.org/maven2/org/antlr/stringtemplate/3.2.1/stringtemplate-3.2.1.jar"

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

echo "== 2/5 拆出 classes2.dex =="
python3 - "$STOCK" "$WORK/classes2.dex" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
open(sys.argv[2], 'wb').write(z.read('classes2.dex'))
PY

echo "== 3/5 反汇编并注入 =="
baksmali disassemble -a "$SRC_API" -o "$WORK/smali2" "$WORK/classes2.dex"
grep -q 'LocalDisplayAdapter$LocalDisplayDevice$1' <(ls "$WORK/smali2/com/android/server/display") \
  || { echo "classes2.dex 里没有目标类，请确认 services.jar 版本"; exit 1; }
python3 "$HERE/patch_smali.py" "$WORK/smali2" \
  "$WORK/bridge/com/android/server/display/AodDozeBridge.smali"

echo "== 4/5 回编译 =="
smali assemble -a "$DEX_API" -o "$WORK/classes2-patched.dex" "$WORK/smali2"

echo "== 5/5 重新打包 =="
python3 "$HERE/repack_jar.py" "$STOCK" "$OUT/services.jar" \
  "classes2.dex=$WORK/classes2-patched.dex"

MODDIR="$WORK/module"
rm -rf "$MODDIR"; mkdir -p "$MODDIR/system/framework"
cp "$HERE/magisk/module.prop" "$MODDIR/"
STOCK_SHA="$(sha256sum "$STOCK" | cut -d' ' -f1)"
sed "s|@STOCK_SHA256@|$STOCK_SHA|" "$HERE/magisk/customize.sh" > "$MODDIR/customize.sh"
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
