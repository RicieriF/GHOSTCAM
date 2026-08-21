# GHOSTCAM Codex Device Test Policy

This repository uses a rooted physical Motorola device for validation.

## Branch safety
- Work only on `ghostcam-shell` unless the user explicitly authorizes another branch.
- Treat `main` as the Golden Core. Do not modify, rebase, merge into, reset, or force-push `main` during diagnosis.
- Reuse draft PR #3 for GHOSTCAM shell work.

## Primary validation rule
Every meaningful camera-core change must be validated on the physical Motorola device before merge.

## Allowed automatic device commands
Codex may run these without asking again when diagnosing GHOSTCAM:
- `adb devices`
- `adb shell pidof ...`
- `adb shell dumpsys ...`
- `adb logcat ...`
- `adb shell am force-stop ...`
- `adb shell am broadcast ...`
- `adb shell monkey -p ... 1`
- read-only `adb shell pm ...` queries
- `/data/adb/lspd/cli scope list ...` through `adb shell su -c` for read-only Vector diagnostics
- the repository script `powershell -ExecutionPolicy Bypass -File .\tools\ghostcam-test.ps1 ...`

## Commands requiring explicit human approval
Do not automatically run:
- `adb reboot`
- any write to Vector/LSPosed scope
- `adb push` or commands that replace phone files
- Magisk module changes
- package install/uninstall
- clearing app data

## Forbidden during autonomous diagnosis
Never run without a separate explicit user request:
- `fastboot flash`
- `fastboot erase`
- partition writes
- bootloader operations
- `rm -rf`
- deleting Magisk/Vector modules
- factory reset or destructive storage operations

## Current target
Module package: `io.github.zensu357.camswap`
Camera package: `com.motorola.camera3`
Vector CLI: `/data/adb/lspd/cli`

## Current diagnostic objective
Prove the original camera-substitution Golden Core on the physical Motorola before adding product shell features.

Known-good evidence includes:
- Vector loads `io.github.zensu357.camswap` into `com.motorola.camera3`.
- `Api101ModuleMain` loads successfully.
- `onPackageReady` executes.
- CameraDeviceImpl/Camera2 hooks install.
- Native hook initializes successfully.
- ImageReader surfaces are captured.

Current gating issue is IPC/config delivery for private video access. Do not rewrite Camera1, Camera2, native hooks, or the player unless logs prove an exact failure there.

## Autonomous test loop
1. Run `tools/ghostcam-test.ps1 -Mode doctor`.
2. If injection is absent, diagnose Vector/scope/process state only.
3. If injection is present, run `tools/ghostcam-test.ps1 -Mode ipc`.
4. Inspect `ACTION_UPDATE_CONFIG` receiver delivery and `Exported Denial` evidence.
5. Correlate PID, sender UID, receiver filter, and timestamps.
6. Make only the smallest evidence-backed source change.
7. Build/CI validate.
8. Ask for human approval before installing a new APK or performing a reboot.
9. After installation/reboot approval, rerun the physical-device tests.

## Stop conditions
Stop autonomous execution and report findings when:
- a source-code change is required;
- APK installation is required;
- reboot is required;
- any destructive or privileged write is required;
- evidence is ambiguous between multiple root causes.

Do not guess. Preserve logs and quote the exact failing lines in the diagnosis.
