# TEST hardening contract

This document describes changes that are intentionally staged only on `X11TEST` and `X11-X0TEST` until physical Android validation is complete.

## Architecture that must not change

- `X11TEST` keeps the `X11APP` dynamic multi-monitor model.
- `X11-X0TEST` keeps one fixed Manager display: Monitor 1 / `:0` / X0.
- The embedded Lorie renderer remains one visible renderer whose Binder connection follows the selected display.
- OpenRC and systemd keep separate service generation and lifecycle handling.
- The validated PulseAudio transport remains intact; control-plane `pactl` checks are advisory after retries while the finite PCM stream remains authoritative.

## Hardening under validation

- Monitor log selection is scoped to the exact display plus the Home lifecycle of the container currently owning that display. A monitor can never borrow another monitor's history.
- Empty synthetic monitor operations are not considered viewable logs and cannot be minimized into notifications.
- Log archives expose an explicit loaded state; monitor log actions wait for restoration before clearing or opening history.
- Completed monitor/setup operations flush their final snapshot before releasing lifecycle ownership.
- TigerVNC process leases bind PID to `/proc/<pid>/stat` start time and validate process identity before TERM/KILL. TCP readiness requires LISTEN state.
- Graphical-user selection is one-shot. If persistence fails after the launcher was replaced, the launcher is rolled back to the previous effective selection.
- Container sidecar/user files use same-directory temporary files followed by rename/move.
- Operation notifications use an explicit Android notification group and summary.
- Exact/custom resolution can be applied with either the check button or IME Done.

## CI validation

The TEST branches run unit tests and release APK builds. Appearance testing covers API 26, 31, 32 and 34, with the native CMake cache pointed at `embedded-lorie/.cxx`. API 32 receives one clean retry for the known Compose semantics/emulator race, but a repeated failure remains a failure. Rootfs compatibility is wired for the active stable/test branch names and continues to audit Alpine, Debian, Ubuntu and ARM64 Wayland package plans.

Nothing in this document authorizes promotion to `X11APP` or `X11-0nly`; promotion happens only after physical validation.
