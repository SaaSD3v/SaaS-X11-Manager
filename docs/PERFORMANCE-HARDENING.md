# Runtime performance hardening

This document tracks runtime-performance work validated on the TEST branches before promotion to the corresponding stable branches.

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

### Tranche 2 — VNC readiness collapse

- TigerVNC LISTEN readiness is polled inside one DroidSpaces invocation instead of entering the container every 250 ms.
- The fixed 750 ms delay after the VNC desktop launch is removed. The Manager now verifies both recorded server/session PID+start-time leases plus the TCP LISTEN state across a short bounded stability window.

### Tranche 3 — X11 lifecycle, UI and log batching

- Integrated X11 process, socket-file and kernel-socket readiness now use one root transaction per poll instead of three libsu round-trips.
- X11 Start returns the result of its authoritative desktop handshake. `SessionAccessManager` rechecks only when the first bounded handshake missed a session that was still settling.
- Reconciliation reuses the container snapshot when no stale bind was changed, and server startup reuses the already-known ownership snapshot.
- Stop/Stop All pass known ownership/container snapshots through the lifecycle and avoid a full container-list read for every monitor.
- A successful Stop All applies one final runtime snapshot instead of reading and applying the same state twice.
- `ViewModelLogger` delivers retained entries as one batch. Compose state, operation observers and the durable-write debounce are invalidated once per visible log burst.
- Compose screens collect `StateFlow` through lifecycle-aware collectors, stopping background UI collection when the Activity is not active.
- A redundant root `test -f` before every single-container config read was removed; the authoritative config read already handles a missing file.
- PulseAudio configuration avoids the atomic rewrite when `enable_pulseaudio` already has the requested effective value, and avoids rewriting an already-absent key during restore.

## Remaining runtime work

1. Add a single-operation rootfs access transaction for user/profile edits so image rootfs is mounted once per operation.
2. Bound PulseAudio host-core startup with a true global deadline; nested control probes must not multiply the outer retry budget.
3. Reuse Termux UID/network/runtime facts across one graphical Start.
4. Defer nonessential `droidspaces check` UI diagnostics so they cannot queue ahead of a user Start.
5. Reorder package repository preparation so APT/APK indexes are refreshed only when required and only once per transaction where possible.
6. Add an offline capability probe for stopped rootfs before temporarily starting a container solely for wizard detection.
7. Replace the legacy whole-`/proc` PulseAudio migration scan with an ownership-safe bounded lookup; it currently runs only on cold recovery, not the warm path.
8. Serialize explicit VNC toolbar commands if device traces show out-of-order writes under rapid repeated input.

## Physical validation

Filter Android Logcat by `SaaSPerf`. Example format:

```
operation=graphic-start:integrated_x11 target=<container> stage=user.prepare elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> stage=audio.prepare-host elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> stage=x11.start-session elapsed_ms=...
operation=graphic-start:integrated_x11 target=<container> complete=success elapsed_ms=...
```

Compare the same container/session twice: first cold, then warm. Keep the user, access mode and network mode unchanged between runs.
