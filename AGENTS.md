# AGENTS.md

ROM-side `services.jar` patch that relights the panel at the full-screen AOD OFF→DOZE recovery edge
on Xiaomi/HyperOS. Every smali target is tied to one exact firmware build.

Verified on Xiaomi 14 (`houji`), Android 16, `OS3.0.303.0.WNCCNXM` — 5 of 5 recovery edges relit,
confirmed in dmesg. The smali baseline is that device's own `services.jar`.

This is the `rom-patch` branch and holds only the ROM patch. The LSPosed module implementation of the
same fix, plus `ARTICLE.md` (the authoritative Chinese write-up of the HAL + N2 kernel
`is_backlight_set_skip()` LP1 rejection, rejected alternatives and logs) live on `main`, verified on
a *different* device (Xiaomi 14 Pro / `shennong` / `OS3.0.307.0.WNBCNXM`). Reflection targets and
smali offsets are not interchangeable between the two. Do not merge the branches without being asked.

## Layout

| Path | Role |
| --- | --- |
| `build.sh` | Only entry point; stock jar in, `out/services.jar` + Magisk zip out |
| `patch_smali.py` | The two smali injections; fails loudly on any signature mismatch |
| `repack_jar.py` | Repacks the jar with dex entries `stored` and 4-byte aligned |
| `src/com/android/server/display/AodDozeBridge.java` | The only class added to `classes2.dex` |
| `stubs/` | Compile-only shims for hidden framework APIs; never shipped (`build.sh` d8's only `AodDozeBridge.class`) |
| `magisk/` | `module.prop` and `customize.sh` for the flashable zip |

## Build

```shell
./build.sh /path/to/stock/services.jar
```

Must be the target device's **own** `services.jar`. First run downloads JDK 17, smali 3.0.9, r8 and
deps into `.tools/` (needs network). Outputs `out/services.jar` and `out/aod-doze-bridge-rom.zip`;
the Magisk installer aborts if the device's `services.jar` sha256 differs from the build baseline.
`.tools/`, `.work/`, `out/` are gitignored and large (~700 MB).

No JDK on `PATH` in this environment — `build.sh` provides its own under `.tools/jdk-17*` and
prepends it, so run the script rather than invoking `javac` directly. If a previous run left a
truncated `.tools/jdk-17*` (no `bin/`), delete that directory and re-run; the script only re-extracts
when the directory is absent.

## How the patch works

Two injection points in `LocalDisplayAdapter$LocalDisplayDevice$1`:

1. top of `setDisplayState(I)V` → `onDisplayState()` records the OFF→DOZE edge, touches nothing;
2. `setDisplayBrightness(FF)V` is renamed to `aodBridgeSetDisplayBrightness` and a same-named wrapper
   is added: `beginBrightness()` → original → `endBrightness()` in a `.catchall`.

Net panel ordering on a recovery edge is the working first-lock ordering
`NORMAL -> write brightness -> DOZE`.

**The edge must be remembered across two events.** `DisplayPowerController` splits a recovery into a
state request and a brightness request: `val$brightnessState` on the state change is `-1.0`, and the
real doze brightness arrives ~70 ms later as its own request. v2 injected only at `setDisplayState`
and logged `brightness=-1.0` on 9 of 10 edges. Do not "simplify" back to a single injection point;
`sPendingEdge` exists for this reason.

Both sleeps are empirical: `setDisplayPowerMode(NORMAL)` needs ~27 ms to reach the panel and a
brightness write ~50 ms to reach the kernel, so v2's single 16 ms delay still lost the race
(`skip set backlight ... due to LP1 on` in dmesg). `PANEL_SETTLE_MS` / `COMMIT_DELAY_MS` are the
knobs to raise if the log shows a relight but the panel stays dark.

`LocalDisplayAdapter.mDozeBrightness` looks like a brightness fallback but is written from
`animateScreenBrightness()` only while the screen state is ON, so it can hold a daylight value.
Do not use it.

## Verification

No tests, no lint, no typecheck config, no CI. Compiling is the only offline check; real validation
requires flashing to a rooted device.

```shell
adb logcat -s AodDozeBridge
```

All log lines go to logcat under the tag `AodDozeBridge`; there is no log file. Start capturing
before triggering the recovery — logcat only keeps a bounded ring buffer and nothing survives a
reboot. A healthy recovery is two lines:

```text
armed OFF->DOZE edge, display=0 state=3
relighting display 0 state=3 with brightness 0.0304
```

Only the first line means the edge was recorded but no brightness request matched.

```shell
adb shell su -c 'dmesg | grep -iE "backlight|LP1"'
```

tells you whether the write actually reached the panel: `set 51 backlight N` after the `relighting`
line is success; `skip set backlight ... due to LP1 on` means it landed after LP1 and the sleeps are
too short. Note the framework's *own* write is rejected that way on every recovery — that is stock
behaviour, not a regression.

If *neither* the expected `AodDozeBridge` log lines nor the framework's own unconditional
`setDisplayState(id=..., state=DOZE)` line appears, the process died inside the injection —
check `adb logcat -b crash -d`, not a tag filter.

`su` is at `/system/bin/su` on the verified device (KernelSU); plain `su` is not on `PATH` for the
`shell` user, so the `dmesg` check above needs root. `logcat` is readable without root.

Do not claim a behavior change is verified without device logs — say what you could not check.

`build.sh` is not byte-reproducible: rebuilding the same input twice yields a different
`classes2.dex` sha256 (smali/dex ordering). Compare `baksmali` output, not jar hashes, when checking
whether a change altered anything. The Magisk installer only checks the *stock* jar's hash, so this
does not affect installs.

## Fragility rules

- **Never let `invoke-custom` reach the dex.** `services.jar` is on the boot classpath; ART calls
  `Runtime::Abort` (native abort — no Java `catch`, no smali `.catchall` can contain it) while
  resolving a `StringConcatFactory` bootstrap method. javac 17 emits exactly that for `"a" + x`,
  which killed system_server inside `relightImpl` in v1 and v2.0 on the first log line, before
  anything reached logcat. `build.sh` compiles with `-XDstringConcat=inline` and `patch_smali.py`
  fails the build if any `invoke-custom`/`invoke-polymorphic` survives. Stock services.jar contains
  zero `StringConcatFactory` references — keep it that way. Same caution applies to lambdas and
  method references.
- Every bridge entry point swallows `Throwable` and degrades to a no-op. This is intentional: a
  framework mismatch must not bootloop the device. Never make these throw.
- `patch_smali.py` exact-matches the `val$*` fields and both method signatures it depends on, and
  keeps `setDisplayState`'s `.registers` unchanged (the hook only uses v0–v2, and no local is live at
  the top of the method). It fails loudly on mismatch — keep it that way rather than loosening the
  match.
- The anonymous class index (`$1`) is hardcoded. If a firmware reorders those classes, the script
  errors out rather than patching the wrong one.

## Conventions

- Docs are Chinese (`README.md`); code comments and this file are English. Write new docs in Chinese.
- Version bumps go in `magisk/module.prop` (`version` + `versionCode`).
- `README.md` carries the version history including what v1/v2 got wrong; keep it truthful when
  changing behavior — the wrong root-cause attributions in that history cost two flash cycles.

## Version control

Colocated Jujutsu + git; git HEAD is detached because jj drives it. Use `jj` for history and commits.
This branch (`rom-patch`) is rooted directly on `main` and shares no files with it.
