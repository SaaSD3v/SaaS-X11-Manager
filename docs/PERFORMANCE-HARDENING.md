# TEST performance hardening

This document tracks the runtime-performance pass on the TEST branches. Stable branches are intentionally untouched.

## Invariants

- Do not weaken process ownership checks, PID/start-time validation or atomic configuration writes.
- Do not change the X11TEST multi-monitor model.
- Do not introduce multi-monitor behavior into X11-X0TEST.
- A finite PulseAudio PCM stream remains the authoritative audio data-path proof.
- Performance telemetry must never log VNC passwords, PulseAudio cookies or raw shell payloads.

## Implemented in the first X11TEST tranche

- VNC cleanup no longer pays two unconditional one-second sleeps when no Manager-owned process exists. TERM/KILL waits are bounded short polls and retain the same fail-closed lease identity checks.
- PulseAudio client validation performs the finite PCM playback proof before advisory `pactl info` diagnostics. User/root control probes are one bounded one-second attempt instead of three five-second attempts each.
- Graphical-user preparation reuses one persisted-selection read for both effective selection and rollback state, avoiding a duplicate DroidSpaces command or stopped-rootfs mount cycle.
- Unchanged graphical-user and launcher files avoid a final rename when `cmp` is available, while retaining the existing temp-file atomic fallback.
- ANSI parsing reuses a compiled escape-pattern and skips regex work entirely for non-ANSI strings.
- Terminal path redaction uses literal replacement rather than compiling a regex for every rendered log row.
- `RuntimePerfTrace` emits Logcat-only `SaaSPerf` stage timings for graphical Start. It deliberately excludes commands and credentials.

## Next runtime tranche

The following findings remain intentionally open until the first tranche is green in CI:

1. Move X11 server readiness polling into one bounded shell transaction instead of repeated `pidof` / socket shell round-trips.
2. Return a structured X11 Start result so SessionAccessManager does not re-run graphical-session confirmation after X11SessionManager already proved it.
3. Make X11 reconciliation return/reuse its container/monitor snapshot instead of immediately listing containers again.
4. Move TigerVNC port readiness polling inside one container command and replace the fixed post-session 750 ms delay with bounded lease/listener readiness.
5. Add a single-operation rootfs access transaction for user/profile edits so image rootfs is mounted once per operation.
6. Bound PulseAudio host-core startup with a true global deadline; nested `timeout 5 pactl` calls must not multiply the outer retry budget.
7. Reuse Termux UID/network/runtime facts across one graphical Start.
8. Skip `enable_pulseaudio` config rewrite when the effective value is already correct.
9. Defer nonessential `droidspaces check` UI diagnostics so they cannot queue ahead of a user Start.
10. Consolidate Stop/Stop All post-validation snapshots instead of reading the full runtime twice.
11. Reorder package repository preparation so APT/APK indexes are refreshed only when required and only once per transaction where possible.
12. Add an offline capability probe for stopped rootfs before temporarily starting a container solely for wizard detection.
13. Batch durable log change notification per ViewModelLogger flush rather than per retained line.

## Physical validation

Filter Android Logcat by `SaaSPerf`. Example format:

```
operation=graphic-start:integrated_x11 target=<container> stage=user.prepare elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> stage=audio.prepare-host elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> stage=x11.start-session elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> complete=success elapsed_ms=...
```

Compare the same container/session twice: first cold, then warm. Keep the user, access mode and network mode unchanged between runs.
