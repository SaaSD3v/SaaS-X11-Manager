# TEST performance hardening

This document tracks the runtime-performance pass on the TEST branches. Stable branches are intentionally untouched.

## Invariants

- Do not weaken process ownership checks, PID/start-time validation or atomic configuration writes.
- Do not change the X11TEST multi-monitor model.
- Do not introduce multi-monitor behavior into X11-X0TEST.
- A finite PulseAudio PCM stream remains the authoritative audio data-path proof.
- Performance telemetry must never log VNC passwords, PulseAudio cookies or raw shell payloads.

## Implemented on X11TEST

### Tranche 1 — hot-path foundations

- VNC cleanup no longer pays two unconditional one-second sleeps when no Manager-owned process exists. TERM/KILL waits are bounded short polls and retain the same fail-closed lease identity checks.
- PulseAudio client validation performs the finite PCM playback proof before advisory `pactl info` diagnostics. User/root control probes are one bounded one-second attempt instead of three five-second attempts each.
- Graphical-user preparation reuses one persisted-selection read for both effective selection and rollback state, avoiding a duplicate DroidSpaces command or stopped-rootfs mount cycle.
- Unchanged graphical-user and launcher files avoid a final rename when `cmp` is available, while retaining the existing temp-file atomic fallback.
- ANSI parsing reuses a compiled escape-pattern and skips regex work entirely for non-ANSI strings.
- Terminal path redaction uses literal replacement rather than compiling a regex for every rendered log row.
- `RuntimePerfTrace` emits Logcat-only `SaaSPerf` stage timings for graphical Start. It deliberately excludes commands and credentials.

### Tranche 2 — collapse readiness polling

- Integrated X11 process + filesystem socket + kernel UNIX-socket readiness is polled inside one bounded root-shell transaction instead of repeatedly crossing Kotlin/libsu for `pidof`, `test -S` and `/proc/net/unix`.
- Integrated X11 Start returns a structured result containing runtime, command-channel and graphical-session confirmation. SessionAccessManager now skips the old second desktop synchronization on the normal success path and retains it only as a recovery retry.
- TigerVNC LISTEN readiness is polled inside one DroidSpaces invocation instead of entering the container every 250 ms.
- The fixed 750 ms delay after the VNC desktop launch is removed. The Manager now verifies both recorded server/session PID+start-time leases plus the TCP LISTEN state across a short bounded stability window.

## Remaining runtime work

1. Make X11 reconciliation return/reuse its container/monitor snapshot instead of immediately listing containers again.
2. Add a single-operation rootfs access transaction for user/profile edits so image rootfs is mounted once per operation.
3. Bound PulseAudio host-core startup with a true global deadline; nested control probes must not multiply the outer retry budget.
4. Reuse Termux UID/network/runtime facts across one graphical Start.
5. Skip `enable_pulseaudio` config rewrite when the effective value is already correct.
6. Defer nonessential `droidspaces check` UI diagnostics so they cannot queue ahead of a user Start.
7. Consolidate Stop/Stop All post-validation snapshots instead of reading the full runtime twice.
8. Reorder package repository preparation so APT/APK indexes are refreshed only when required and only once per transaction where possible.
9. Add an offline capability probe for stopped rootfs before temporarily starting a container solely for wizard detection.
10. Batch durable log change notification per ViewModelLogger flush rather than per retained line.
11. Port common changes to X11-X0TEST and separately remove its fixed-X0 whole-`/proc` fallback without introducing multi-monitor behavior.

## Physical validation

Filter Android Logcat by `SaaSPerf`. Example format:

```
operation=graphic-start:integrated_x11 target=<container> stage=user.prepare elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> stage=audio.prepare-host elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> stage=x11.start-session elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> complete=success elapsed_ms=...
```

Compare the same container/session twice: first cold, then warm. Keep the user, access mode and network mode unchanged between runs.
