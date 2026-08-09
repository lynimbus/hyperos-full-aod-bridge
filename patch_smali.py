#!/usr/bin/env python3
"""把 AodDozeBridge 的调用点注入 services.jar 的反汇编树。

用法: patch_smali.py <smali 反汇编目录> <AodDozeBridge.smali>

build.sh 会对原厂 jar 里的每个 classes*.dex 调用本脚本一次。脚本按树内容自行
决定做什么，修改过的树会写入标记文件 .aod_patched 供 build.sh 判断回编译范围：

1. LocalDisplayAdapter$LocalDisplayDevice$1.smali（若在该树中，硬校验，不符即报错）：
   a. setDisplayState(I)V 开头调用 AodDozeBridge.onDisplayState() 记录 OFF→DOZE 边沿，
      其返回值即 v3 预点亮亮度（无值为 NaN）；可用时立即调用包装方法
      setDisplayBrightness(FF)V 完成 NORMAL→写亮度→DOZE 整轮——不等 ~70ms 后的亮度请求。
      寄存器数不变，只用 v0-v3（方法开头除 p0/p1 外没有存活的局部变量）。
   b. setDisplayBrightness(FF)V 原方法更名为 aodBridgeSetDisplayBrightness(FF)V，新增同名
      包装方法：先 beginBrightness()，命中边沿时改用桥接给出的亮度调用原方法，finally 里
      endBrightness() 把面板送回 DOZE。类内所有调用点不变，自动走包装方法。
      该包装同时是预点亮值缺失时的回退路径（在亮度请求边沿执行同样的整轮）。
   c. 新增私有辅助方法 aodBridgeDisplayId()I，返回逻辑 displayId。
2. DisplayPowerController 预点亮钩子（若在该树中）：尽力而为地在框架算好息屏亮度后立即
   调用 AodDozeBridge.setDozeBrightness(F)，让状态边沿能提前拿到真实息屏亮度。按固件代际
   匹配两个注入点（先旧后新，命中其一即可）：
   a. 旧固件：updateAodAutoBrightness 的日志拼接处（newAodScreenAutoBrightness 所在寄存器）；
   b. 新固件（Android 16 亮度重构）：updatePowerStateInternal 里
      DisplayBrightnessState.getBrightness() 的 move-result 之后——DisplayBrightnessController
      策略链刚算出最终亮度的最早点，等价于旧固件的 updateAodAutoBrightness。
   两个点都匹配不到、且该树确实含 DisplayPowerController 时只告警不报错——补丁仍可用，
   只是退回亮度请求边沿点亮（与旧版行为一致）。不含该类的树不可能命中，静默跳过。
3. 两处都不在的树不做任何事。

为什么必须在状态边沿之外还保留亮度请求边沿的注入：状态请求携带的 val$brightnessState 是
-1.0，真实息屏亮度在几十毫秒后作为独立请求送来（实机日志证实 9/10 次边沿如此）。v3 的
预点亮钩子只是让它提前到达；钩子缺失或值不可用时仍要等亮度请求。
"""
import os
import re
import shutil
import sys

ANON = "Lcom/android/server/display/LocalDisplayAdapter$LocalDisplayDevice$1;"
DEV = "Lcom/android/server/display/LocalDisplayAdapter$LocalDisplayDevice;"
BRIDGE = "Lcom/android/server/display/AodDozeBridge;"

# setDisplayState 注入点用的临时寄存器数量（v0..v3）。
STATE_SCRATCH = 4

STATE_HOOK = f"""
    invoke-direct {{p0}}, {ANON}->aodBridgeDisplayId()I

    move-result v0

    iget v1, p0, {ANON}->val$oldState:I

    move v2, p1

    invoke-static {{v0, v1, v2}}, {BRIDGE}->onDisplayState(III)F

    move-result v2

    # v3 prelight: onDisplayState 在状态边沿返回真实息屏亮度时立即点亮，不等亮度请求。
    const/4 v3, 0x0

    cmpl-float v3, v2, v3

    if-lez v3, :aod_bridge_no_prelight

    invoke-direct {{p0, v2, v2}}, {ANON}->setDisplayBrightness(FF)V

    :aod_bridge_no_prelight
"""

HELPERS = f"""
.method private aodBridgeDisplayId()I
    .registers 4

    iget-object v0, p0, {ANON}->this$1:{DEV}

    invoke-static {{v0}}, {DEV}->-$$Nest$fgetmPhysicalDisplayId({DEV})J

    move-result-wide v1

    invoke-virtual {{v0, v1, v2}}, {DEV}->getDisplayId(J)I

    move-result v0

    return v0
.end method

.method private setDisplayBrightness(FF)V
    .registers 9
    .param p1, "brightnessState"    # F
    .param p2, "sdrBrightnessState"    # F

    invoke-direct {{p0}}, {ANON}->aodBridgeDisplayId()I

    move-result v0

    iget v1, p0, {ANON}->val$state:I

    iget-object v2, p0, {ANON}->val$token:Landroid/os/IBinder;

    invoke-static {{v0, v1, v2, p1}}, {BRIDGE}->beginBrightness(IILandroid/os/IBinder;F)F

    move-result v3

    const/4 v4, 0x0

    cmpl-float v4, v3, v4

    if-lez v4, :aod_bridge_original

    goto :aod_bridge_skip

    :aod_bridge_original
    :try_start_aod_bridge
    invoke-direct {{p0, p1, p2}}, {ANON}->aodBridgeSetDisplayBrightness(FF)V
    :try_end_aod_bridge
    .catchall {{:try_start_aod_bridge .. :try_end_aod_bridge}} :catchall_aod_bridge

    :aod_bridge_skip
    invoke-static {{}}, {BRIDGE}->endBrightness()V

    return-void

    :catchall_aod_bridge
    move-exception v5

    invoke-static {{}}, {BRIDGE}->endBrightness()V

    throw v5
.end method
"""

# 注入依赖的字段，缺一不可。
REQUIRED_FIELDS = [
    ".field final synthetic val$oldState:I",
    ".field final synthetic val$state:I",
    ".field final synthetic val$token:Landroid/os/IBinder;",
    ".field final synthetic this$1:" + DEV,
]

STATE_DEF = re.compile(
    r"\.method private setDisplayState\(I\)V\n"
    r"    \.registers (\d+)\n"
    r'    \.param p1, "state"    # I\n')

BRIGHTNESS_DEF = re.compile(
    r"\.method private setDisplayBrightness\(FF\)V\n"
    r"    \.registers (\d+)\n")

# updateAodAutoBrightness 里的日志形如 "updateAodAutoBrightness: newAodScreenAutoBrightness="
# + value，javac 会编译成 StringBuilder.append(F)。在第一个 append(F) 前注入桥接调用，
# 该指令的第二个寄存器（浮点参数）就是 value。
DPC_METHOD = re.compile(r"\.method [^\n]*updateAodAutoBrightness[^\n]*\n")
DPC_CONST = re.compile(r'const-string v\d+, "[^"]*updateAodAutoBrightness[^"]*"\n')
DPC_APPEND_F = re.compile(
    r"    invoke-virtual \{(?:v\d+, )?v(\d+)\}, "
    r"Ljava/lang/StringBuilder;->append\(F\)Ljava/lang/StringBuilder;\n")

# 新固件（Android 16 亮度重构）注入点：updatePowerStateInternal 里
# DisplayBrightnessController.updateBrightness 返回后第一次 getBrightness() 的 move-result，
# 该寄存器就是框架最终将下发的亮度（含 DOZE 策略/clamp 之前的值）。取方法体内第一处匹配。
DPC_METHOD_NEW = re.compile(r"\.method private updatePowerStateInternal\(\)V\n")
DPC_GET_BRIGHTNESS_NEW = re.compile(
    r"    invoke-virtual \{v\d+\}, "
    r"Lcom/android/server/display/DisplayBrightnessState;->getBrightness\(\)F\n"
    r"\n"
    r"    move-result v(\d+)\n")


def fail(msg):
    print("FAIL: " + msg, file=sys.stderr)
    sys.exit(1)


def check_bridge(bridge_smali):
    """桥接类不能带 boot classpath 上跑不了的指令。

    services.jar 在 boot classpath 上，ART 解析 invoke-custom 的 bootstrap method 时会
    Runtime::Abort（native abort，Java 的 catch 拦不住），进程直接没。v1 就是死在这里：
    javac 17 默认把字符串拼接编成 invoke-dynamic → StringConcatFactory。
    build.sh 已经加了 -XDstringConcat=inline，这里再兜一道，避免以后又踩。
    """
    text = open(bridge_smali, encoding="utf-8").read()
    for op in ("invoke-custom", "invoke-polymorphic"):
        if op in text:
            fail("桥接类里出现 %s，boot classpath 上会导致 ART abort。"
                 "检查 build.sh 的 -XDstringConcat=inline 是否生效" % op)


def has_dpc_class(tree):
    """该树是否含有 DisplayPowerController（预点亮钩子的唯一宿主类）。"""
    return os.path.exists(os.path.join(
        tree, "com", "android", "server", "display", "DisplayPowerController.smali"))


def inject_dpc_hook(tree):
    """尽力而为的预点亮钩子（见文件头第 2 条）。返回注入的方法数。"""
    injected = 0
    for root, _, files in os.walk(tree):
        for name in sorted(files):
            # 钩子点只可能在 DisplayPowerController 的类文件里（两个注入点都是它的
            # private 方法）；跳过其余文件，避免全树 ~6k 个 smali 全量读入。
            if not name.endswith(".smali") or not name.startswith("DisplayPowerController"):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8").read()
            if "setDozeBrightness" in text:
                continue  # 本文件已注入过
            # 旧固件钩子：updateAodAutoBrightness 的日志拼接处。
            m = DPC_METHOD.search(text)
            if m:
                end = text.find(".end method", m.end())
                body = text[m.end():end]
                cs = DPC_CONST.search(body)
                if cs:
                    app = DPC_APPEND_F.search(body[cs.end():])
                    if app:
                        reg = app.group(1)
                        hook = "    invoke-static {v%s}, %s->setDozeBrightness(F)V\n" % (reg, BRIDGE)
                        pos = m.end() + cs.end() + app.start()
                        text = text[:pos] + hook + text[pos:]
                        open(path, "w", encoding="utf-8").write(text)
                        injected += 1
                        print("已注入 DPC 预点亮钩子（%s: v%s）" % (name, reg))
                        continue
            # 新固件钩子：updatePowerStateInternal 里 getBrightness() 之后。
            m = DPC_METHOD_NEW.search(text)
            if m:
                end = text.find(".end method", m.end())
                body = text[m.end():end]
                gb = DPC_GET_BRIGHTNESS_NEW.search(body)
                if gb:
                    reg = gb.group(1)
                    hook = "    invoke-static {v%s}, %s->setDozeBrightness(F)V\n" % (reg, BRIDGE)
                    pos = m.end() + gb.end()
                    text = text[:pos] + "\n" + hook + text[pos:]
                    open(path, "w", encoding="utf-8").write(text)
                    injected += 1
                    print("已注入 DPC 预点亮钩子（新固件 %s: v%s）" % (name, reg))
    return injected


def main():
    if len(sys.argv) != 3:
        fail("用法: patch_smali.py <smali 目录> <AodDozeBridge.smali>")
    tree, bridge_smali = sys.argv[1], sys.argv[2]
    check_bridge(bridge_smali)
    disp = os.path.join(tree, "com", "android", "server", "display")
    target = os.path.join(disp, "LocalDisplayAdapter$LocalDisplayDevice$1.smali")
    modified = False

    if os.path.exists(target):
        text = open(target, encoding="utf-8").read()
        if "AodDozeBridge" in text:
            fail("该 smali 已经打过补丁")

        for needle in REQUIRED_FIELDS:
            if text.count(needle) != 1:
                fail("注入所需的字段与预期不符: " + needle)

        dev = os.path.join(disp, "LocalDisplayAdapter$LocalDisplayDevice.smali")
        dev_text = open(dev, encoding="utf-8").read()
        for name in ("-$$Nest$fgetmPhysicalDisplayId", "getDisplayId(J)I"):
            if name not in dev_text:
                fail("LocalDisplayDevice 里找不到 " + name)

        # 1. 原 setDisplayBrightness 更名。类内原有的调用点保持写 setDisplayBrightness 不变，
        #    于是自动落到下面新增的同名包装方法上。
        hits = BRIGHTNESS_DEF.findall(text)
        if len(hits) != 1:
            fail("setDisplayBrightness(FF)V 定义与预期不符（签名或寄存器数变化）")
        text = BRIGHTNESS_DEF.sub(
            ".method private aodBridgeSetDisplayBrightness(FF)V\n"
            "    .registers %s\n" % hits[0], text, count=1)

        # 2. setDisplayState 开头记录边沿并尝试预点亮，寄存器数不动。
        hits = STATE_DEF.findall(text)
        if len(hits) != 1:
            fail("setDisplayState(I)V 定义与预期不符（签名或 .param 变化）")
        registers = int(hits[0])
        # .registers 含参数：p0/p1 各占一个，其余才是可用的局部寄存器。
        if registers - 2 < STATE_SCRATCH:
            fail("setDisplayState(I)V 只有 %d 个局部寄存器，注入需要 %d 个"
                 % (registers - 2, STATE_SCRATCH))
        text = STATE_DEF.sub(lambda m: m.group(0) + STATE_HOOK, text, count=1)

        text = text.rstrip("\n") + "\n" + HELPERS
        open(target, "w", encoding="utf-8").write(text)
        shutil.copy(bridge_smali, os.path.join(disp, "AodDozeBridge.smali"))
        modified = True
        print("已注入 %s（setDisplayState .registers %d 保持不变）" % (target, registers))
    # 其他树里没有 $1，跳过（build.sh 已确认至少一个树里有）。

    dpc = inject_dpc_hook(tree)
    if dpc == 0 and has_dpc_class(tree):
        print("WARNING: 未找到预点亮钩子点（旧固件 updateAodAutoBrightness / 新固件 "
              "updatePowerStateInternal.getBrightness），预点亮（v3 的主要延迟优化）将不生效；"
              "补丁仍按亮度请求边沿点亮（与旧版行为一致）。", file=sys.stderr)
    elif dpc > 1:
        print("WARNING: updateAodAutoBrightness 匹配到 %d 处，已全部注入；"
              "如非预期请核对固件。" % dpc, file=sys.stderr)

    if modified or dpc > 0:
        open(os.path.join(tree, ".aod_patched"), "w", encoding="utf-8").write("1\n")


if __name__ == "__main__":
    main()
