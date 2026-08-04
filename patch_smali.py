#!/usr/bin/env python3
"""把 AodDozeBridge 的调用点注入 LocalDisplayAdapter$LocalDisplayDevice$1.smali。

用法: patch_smali.py <smali 反汇编目录> <AodDozeBridge.smali>

两处注入，和 LSPosed 模块的结构一致（只有这个结构在实机上真正点亮过面板）：

1. setDisplayState(I)V 方法开头调用 AodDozeBridge.onDisplayState()，只记录 OFF→DOZE 边沿。
   寄存器数不变，只用 v0-v2（方法开头除 p0/p1 外没有存活的局部变量）。
2. setDisplayBrightness(FF)V 原方法更名为 aodBridgeSetDisplayBrightness(FF)V，新增同名包装方法：
   先 beginBrightness()，命中边沿时改用桥接给出的亮度调用原方法，finally 里 endBrightness()
   把面板送回 DOZE。类内所有调用点不变，自动走包装方法。

为什么必须在亮度写入点动手：状态变更请求携带的 val$brightnessState 是 -1.0，真实息屏亮度
在几十毫秒后作为独立请求送来（实机日志证实 9/10 次边沿如此），只有那时才知道要写多少。

3. 新增私有辅助方法 aodBridgeDisplayId()I，返回逻辑 displayId。
"""
import os
import re
import shutil
import sys

ANON = "Lcom/android/server/display/LocalDisplayAdapter$LocalDisplayDevice$1;"
DEV = "Lcom/android/server/display/LocalDisplayAdapter$LocalDisplayDevice;"
BRIDGE = "Lcom/android/server/display/AodDozeBridge;"

# setDisplayState 注入点用的临时寄存器数量（v0..v2）。
STATE_SCRATCH = 3

STATE_HOOK = f"""
    invoke-direct {{p0}}, {ANON}->aodBridgeDisplayId()I

    move-result v0

    iget v1, p0, {ANON}->val$oldState:I

    move v2, p1

    invoke-static {{v0, v1, v2}}, {BRIDGE}->onDisplayState(III)V
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

    move p1, v3

    move p2, v3

    :aod_bridge_original
    :try_start_aod_bridge
    invoke-direct {{p0, p1, p2}}, {ANON}->aodBridgeSetDisplayBrightness(FF)V
    :try_end_aod_bridge
    .catchall {{:try_start_aod_bridge .. :try_end_aod_bridge}} :catchall_aod_bridge

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


def fail(msg):
    print("FAIL: " + msg, file=sys.stderr)
    sys.exit(1)


def check_bridge(bridge_smali):
    """桥接类不能带 boot classpath 上跑不了的指令。

    services.jar 在 boot classpath 上，ART 解析 invoke-custom 的 bootstrap method 时会
    Runtime::Abort（native abort，Java 的 catch 拦不住），进程直接没。v1/v2.0 就是死在这里：
    javac 17 默认把字符串拼接编成 invoke-dynamic → StringConcatFactory。
    build.sh 已经加了 -XDstringConcat=inline，这里再兜一道，避免以后又踩。
    """
    text = open(bridge_smali, encoding="utf-8").read()
    for op in ("invoke-custom", "invoke-polymorphic"):
        if op in text:
            fail("桥接类里出现 %s，boot classpath 上会导致 ART abort。"
                 "检查 build.sh 的 -XDstringConcat=inline 是否生效" % op)


def main():
    if len(sys.argv) != 3:
        fail("用法: patch_smali.py <smali 目录> <AodDozeBridge.smali>")
    tree, bridge_smali = sys.argv[1], sys.argv[2]
    check_bridge(bridge_smali)
    disp = os.path.join(tree, "com", "android", "server", "display")
    target = os.path.join(disp, "LocalDisplayAdapter$LocalDisplayDevice$1.smali")
    if not os.path.exists(target):
        fail("找不到 " + target)

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

    # 2. setDisplayState 开头记录边沿，寄存器数不动。
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
    print("已注入 %s（setDisplayState .registers %d 保持不变）" % (target, registers))


if __name__ == "__main__":
    main()
