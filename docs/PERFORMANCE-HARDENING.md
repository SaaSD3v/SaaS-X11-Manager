# Runtime performance hardening

This document tracks runtime-performance work validated on `X11-X0TEST` before promotion to `X11-0nly`. The fixed contract remains one Monitor 1 on `:0`/`X0`; none of these changes imports the dynamic allocator from X11APP.

## Implemented

- X0 process and socket readiness now use one root transaction per poll instead of separate libsu calls.
- The ineffective whole-`/proc` X11 process fallback was removed. Android `app_process` may expose `main` in `/proc/PID/comm`, while the authoritative `pidof` nice-name remains available.
- Home reads one fixed-X11 runtime snapshot instead of separately probing status and PID.
- A successful integrated Start trusts the desktop handshake already completed by `X11SessionManager`, avoiding the same heavy container command twice.
- X0 Start reuses the container inventory for both target lookup and conflicting-owner detection.
- Stop All reuses Home's known container inventory and avoids a duplicate successful post-operation refresh.
- `ViewModelLogger` delivers retained entries as one batch. Compose state, operation observers and durable-write debounce are invalidated once per burst.
- Compose screens use lifecycle-aware `StateFlow` collection, stopping background UI collection while the Activity is inactive.
- A redundant root `test -f` before a single-container config read was removed; the config read already handles absence.
- PulseAudio configuration skips the atomic rewrite when `enable_pulseaudio` already has the requested effective value, including an already-absent key during restore.

## Correctness gates

- `scripts/verify-x11-only.sh` recursively rejects dynamic display allocation and non-`:0`/`X0` production behavior.
- Runtime policy tests pin the one-probe, no-whole-`/proc`, no-double-desktop-confirmation and lifecycle-aware collection contracts.
- Appearance tests create an explicit monitor log before exercising the conditional log action. This matches the UI rule that an empty synthetic log must stay hidden.
- The Android appearance matrix runs on both `X11-X0TEST` and `X11-0nly`, validating UI changes before and after promotion.
- Full unit, Android appearance and release-build validation runs in GitHub Actions because the local workspace does not contain the Gradle distribution or Android SDK.

## Remaining measured-device work

1. Bound PulseAudio cold recovery with one true global deadline.
2. Reuse Termux UID/network facts across one graphical Start.
3. Replace the cold-recovery whole-`/proc` PulseAudio migration scan with an ownership-safe bounded lookup.
4. Capture warm/cold `SaaSPerf` traces on representative Android kernels; CI validates behavior and build correctness, not physical-device latency.
