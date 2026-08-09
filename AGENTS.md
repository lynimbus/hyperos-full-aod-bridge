# AGENTS.md

ROM-side `services.jar` patch that relights the panel at the full-screen AOD OFF→DOZE recovery edge
on Xiaomi/HyperOS. Every smali target is tied to one exact firmware build.

Verified on Xiaomi 14 (`houji`), Android 16, HyperOS **OS3.1** (build.prop
`ro.build.version.incremental=16OS3.1.260809.095052558.QCPECN.S`; the `getprop`
`OS3.0.318.0.WNCCNXM` is a user-rewritten prop, the system content is OS3.1) —
5 of 5 tap-to-wake edges relit, confirmed in dmesg. The smali baseline is that device's own
`services.jar`.

**Do not build from a `OS3.0.303.0.WNCCNXM` stock jar and flash it on this device.** The device
runs OS3.1; the 303 jar's resource IDs are mismatched, and system_server dies in
`AppBatteryTracker.getFloatArray` (`NumberFormatException: "com.android.providers.calendar"`)
→ AMS constructor failure → black screen at boot. The working baseline was recovered by
*un-patching* the device's bundled v2 patch: delete `AodDozeBridge.smali` from `classes.dex`
and revert the `$1` injections in `classes2.dex` (see the firmware-binding note at the top of
`手动修补指南.md`). `classes3.dex`/`classes4.dex` are device-native OS3.1 and
must be kept as-is.

This is the `rom-patch` branch and holds only the ROM patch. The LSPosed module implementation of the
same fix, plus `ARTICLE.md` (the authoritative Chinese write-up of the HAL + N2 kernel
`is_backlight_set_skip()` LP1 rejection, rejected alternatives and logs) live on `main`, verified on
a *different* device (Xiaomi 14 Pro / `shennong` / `OS3.0.307.0.WNBCNXM`). Reflection targets and
smali offsets are not interchangeable between the two. Do not merge the branches without being asked.

## Layout

| Path | Role |
| --- | --- |
| `build.sh` | Only entry point; stock jar in, `out/services.jar` + Magisk zip out |
| `patch_smali.py` | The three smali injections; the two `$1` points fail loudly on any signature mismatch, the prelight hook (legacy `updateAodAutoBrightness` or new `updatePowerStateInternal` `getBrightness`) warns only |
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
the Magisk zip drops the patched jar straight into `system/framework` (no baseline checks).
`.tools/`, `.work/`, `out/` are gitignored and large (~700 MB).

No JDK on `PATH` in this environment — `build.sh` provides its own under `.tools/jdk-17*` and
prepends it, so run the script rather than invoking `javac` directly. If a previous run left a
truncated `.tools/jdk-17*` (no `bin/`), delete that directory and re-run; the script only re-extracts
when the directory is absent.

## How the patch works

Three injection points:

1. top of `setDisplayState(I)V` → `onDisplayState()` records the OFF→DOZE edge and, when the
   doze brightness is already available (v3 prelight), the injected smali immediately calls the
   `setDisplayBrightness` wrapper to run the whole relight cycle — no waiting for the brightness
   request;
2. `setDisplayBrightness(FF)V` is renamed to `aodBridgeSetDisplayBrightness` and a same-named
   wrapper is added: `beginBrightness()` → (v3: nothing) → `endBrightness()` in a `.catchall`.
   When the bridge relights (returns > 0) the wrapper **skips the original method entirely** —
   the bridge writes the panel brightness itself via
   `SurfaceControl.setDisplayBrightness(token, b)`. Calling the original method here is broken
   on this firmware: under Full AOD it takes the “normal brightness” branch and calls
   `updateDozeBrightness(0)`, zeroing the doze brightness (dmesg: the relight write landed as
   `set 51 backlight 0`). The original method still runs for the framework's own brightness
   requests, which arrive after the edge is consumed (bridge returned 0).
3. best-effort hook in `DisplayPowerController` → `setDozeBrightness`: feeds the bridge the
   doze brightness ~70 ms before the adapter delivers it. The injection point is matched by
   firmware generation — legacy `updateAodAutoBrightness` (log-append site), or, on the
   Android 16 brightness-refactor firmware, the first `DisplayBrightnessState.getBrightness()`
   `move-result` inside `updatePowerStateInternal` (right after the `DisplayBrightnessController`
   strategy chain computed the final brightness). Unlike the other two, a mismatch here only
   warns — the patch still works (relight at the brightness edge, same as v2), it just loses
   the latency win. Do not silently ignore that warning: it means this build has no prelight.

Net panel ordering on a recovery edge is the working first-lock ordering
`NORMAL -> write brightness -> DOZE`, in v3 executed at the state edge (~.378) instead of the
brightness edge (~.451); the state edge relights ~66 ms after it fires, versus ~139 ms for the
brightness-edge path.

Measured on device (v3, tap-to-wake): `setDisplayPowerMode(NORMAL)` blocks ~188 ms
(SurfaceFlinger panel-mode switch, hardware-bound — this is the dominant term of the ~213 ms
`armed -> relighting` interval; the brightness write itself is ~25 ms including
`PANEL_SETTLE_MS`). There is no cheap way to shrink the mode switch from a framework patch;
perceived latency is ~300 ms and mostly unavoidable.

**Tap-to-wake and the Local HBM window.** Tapping the screen to wake the AOD triggers a
fingerprint-auth Local HBM window on this device (~360 ms, kernel logs `LHBM_ON_WHITE_1000NIT`
… `LHBM_OFF_AUTH_STOP`). While it is on, the kernel rejects every backlight write
(`skip set backlight N due to LHBM is on`), and the framework does *not* re-send the AOD
brightness afterwards — the panel stays black until an ambient-light change makes it recompute
(v3 symptom: “covering the camera then releasing lights it up”). v3 therefore re-writes the
brightness `LHBM_WINDOW_MS` (400 ms) after the first write. The re-write is unconditional
(no kernel interface to query LHBM state); on non-LHBM edges it is a harmless same-value write.
The 400 ms sleep blocks the DisplayManager thread — a known, accepted cost on recovery edges
only.

**The edge must be remembered across two events.** `DisplayPowerController` splits a recovery into
a state request and a brightness request: `val$brightnessState` on the state change is `-1.0`, and
the real doze brightness arrives ~70 ms later as its own request. v1 injected only at
`setDisplayState` and logged `brightness=-1.0` on 9 of 10 edges. v3's prelight only removes the
wait when `updateAodAutoBrightness` has already computed the value; when it has not, the bridge
must still wait for the brightness request. Do not "simplify" back to a single injection point;
`sPendingEdge` exists for this reason. On the refactor firmware the prelight value is the
`updatePowerStateInternal` strategy output, which is what the brightness request later carries
(no override/clamp passes apply to DOZE brightness) — same guarantee the legacy hook had via
`updateAodAutoBrightness` writing the field that `getDozeBrightness` later read.

brightness write ~50 ms to reach the kernel, so v1's single 16 ms delay still lost the race
(`skip set backlight ... due to LP1 on` in dmesg). `PANEL_SETTLE_MS` / `COMMIT_DELAY_MS` are the
knobs to raise if the log shows a relight but the panel stays dark. (v3 measures the mode
switch itself at ~188 ms of synchronous binder wait — the old ~27 ms figure was the panel-side
latency after the call returned; both matter.)

`LocalDisplayAdapter.mDozeBrightness` looks like a brightness fallback but is written from
`animateScreenBrightness()` only while the screen state is ON, so it can hold a daylight value.
Do not use it.

## Verification

No tests, no lint, no typecheck config, no CI. Compiling is the only offline check; real validation
requires flashing to a rooted device.

```shell
adb logcat -s AodDozeBridge
```

Every log line goes to logcat with tag `AodDozeBridge`. A healthy prelight recovery is two lines,
both stamped at the state edge:

```text
armed OFF->DOZE edge, display=0 state=3 prelight=0.0304
relighting display 0 state=3 with brightness 0.0304
```

`prelight=none` on the first line means the prelight hook had no value for this edge and the
relight fell back to the brightness edge (~70 ms later, same as v2 — still healthy, just
slower). Only the first line means the edge was recorded but no brightness request matched.

```shell
adb shell su -c 'dmesg | grep -iE "backlight|LP1"'
```

tells you whether the write actually reached the panel: `set 51 backlight N` after the `relighting`
line is success; `skip set backlight ... due to LP1 on` means it landed after LP1 and the sleeps are
too short. Note the framework's *own* write is rejected that way on every recovery — that is stock
behaviour, not a regression.

If *neither* the bridge logcat lines nor the framework's own unconditional
`setDisplayState(id=..., state=DOZE)` line appears, the process died inside the injection —
check `adb logcat -b crash -d`, not a tag filter.

`su` is at `/system/bin/su` on the verified device (KernelSU); plain `su` is not on `PATH` for the
`shell` user.

Do not claim a behavior change is verified without device logs — say what you could not check.

`build.sh` is not byte-reproducible: rebuilding the same input twice yields a different
`classes2.dex` sha256 (smali/dex ordering). Compare `baksmali` output, not jar hashes, when checking
whether a change altered anything. The Magisk zip has no hash checks, so this does not affect
installs.

## Fragility rules

- **Never let `invoke-custom` reach the dex.** `services.jar` is on the boot classpath; ART calls
  `Runtime::Abort` (native abort — no Java `catch`, no smali `.catchall` can contain it) while
  resolving a `StringConcatFactory` bootstrap method. javac 17 emits exactly that for `"a" + x`,
  which killed system_server inside `relightImpl` in v1 on the first log line, before
  anything reached logcat. `build.sh` compiles with `-XDstringConcat=inline` and `patch_smali.py`
  fails the build if any `invoke-custom`/`invoke-polymorphic` survives. Stock services.jar contains
  zero `StringConcatFactory` references — keep it that way. Same caution applies to lambdas and
  method references.
- Every bridge entry point swallows `Throwable` and degrades to a no-op. This is intentional: a
  framework mismatch must not bootloop the device. Never make these throw.
- `patch_smali.py` exact-matches the `val$*` fields and both method signatures it depends on, and
  keeps `setDisplayState`'s `.registers` unchanged (the hook only uses v0–v3, and no local is live
  at the top of the method). It fails loudly on mismatch — keep it that way rather than loosening
  the match.
- The anonymous class index (`$1`) is hardcoded. If a firmware reorders those classes, the script
  errors out rather than patching the wrong one.
- The prelight hook is the only best-effort injection: legacy firmware matches
  `updateAodAutoBrightness` (method name + `const-string` + first `StringBuilder.append(F)`
  after it); refactor firmware matches `updatePowerStateInternal` + the first
  `DisplayBrightnessState.getBrightness()` `move-result`. Missing both warns loudly and the build
  still succeeds with the v2 fallback. If the hook's register choice ever looked wrong, verify
  against a disassembly of `DisplayPowerController` on the target firmware before trusting the
  prelight path.
- `build.sh` now round-trips every `classes*.dex` that `patch_smali.py` actually modified
  (marker file `.aod_patched` in the smali tree); unmodified dexes are copied through untouched.

## Conventions

- Docs are Chinese (`README.md`); code comments and this file are English. Write new docs in Chinese.
- Version bumps go in `magisk/module.prop` (`version` + `versionCode`).
- `README.md` carries the version history including what v1/v2 got wrong; keep it truthful when
  changing behavior — the wrong root-cause attributions in that history cost two flash cycles.

## Version control

Colocated Jujutsu + git; git HEAD is detached because jj drives it. Use `jj` for history and commits.
This branch (`rom-patch`) is rooted directly on `main` and shares no files with it.
