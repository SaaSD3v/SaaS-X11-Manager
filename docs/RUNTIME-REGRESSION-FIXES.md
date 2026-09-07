# Audio and Alpine runtime corrections — 2026-09-07

The existing authenticated HOST and NAT listeners remain the transport baseline.
These changes repair runtime ownership, client setup and graphical startup around
those listeners; they do not introduce anonymous listeners or firewall rules.

## Corrected failure paths

- The runtime-generation guard used to create the shared audio directory as root
  before the Termux UID could initialize it. Prepare the two Manager directories
  with their actual Termux owner, repair the old marker and write new markers as
  that UID. No recursive ownership change of the Termux home is performed.
- A responding `pactl info` could hide a stalled Android backend. A bounded,
  quarter-second silent PCM stream now has to drain before reusing a core or
  accepting an AAudio/OpenSL ES backend. Replacement waits for the owned old core
  to stop and refuses to start a second core when ownership is uncertain.
- Root-only cookies, inherited native `PULSE_SERVER` values and two conflicting
  login profiles could keep desktop applications on an old endpoint. Both
  transports now update one canonical client config, install private cookies for
  the selected account and verify that account's connection. A finite stream is
  also checked through the container's actual TCP transport.
- The graphical launchers load that configuration explicitly, including users
  created on launch. Audio finalization occurs after container command readiness
  and before the Manager requests the desktop. Simultaneous start requests are
  serialized so they cannot replace the shared core concurrently.
- NAT now checks the same opt-in setting as HOST. Disabling the integration also
  restores managed non-root client configs and removes their private cookies.
- Alpine socket setup verifies the mounted directory against the source directory
  instead of accepting any mountpoint. The launcher and OpenRC services are
  refreshed in existing containers, so upgrades do not leave older templates live.
- OpenRC recovery uses its explicit crashed status (32), checks that the saved PID
  is absent/dead, and only then clears stale bookkeeping. It never clears state
  for a live PID. A fresh session must remain active across a second status check.
- X11 client probes have deadlines and support either `xset` or `xdpyinfo`.
  Missing optional probe utilities no longer reject an otherwise valid desktop.

On the branch that supports monitor allocation, the launcher resolves its display
from the isolated source directory, avoiding unrelated VNC sockets under `/tmp`.
The single-display branch retains its fixed `:0` contract.

## Regression coverage and limits

`AudioAndAlpineRuntimeTest` executes the generated shell, including complete HOST
and NAT client payloads. It covers endpoint migration, binary cookie preservation,
selected-user setup, failed PCM drain, source selection, stale mount repair and
OpenRC dead/live PID handling. It uses controlled process/network boundaries;
these tests do not emulate Android's audio hardware or the embedded renderer.
The AF_UNIX fixture runs in CI; restrictive local runtimes may skip it explicitly.

The Android CI workflows run the complete unit suite and release builds for both
branches. Real audible output in NAT/HOST and Alpine desktops on the device remain
necessary to confirm the hardware-specific result; CI alone is not that proof.

OpenRC semantics were checked against its [status implementation](https://github.com/OpenRC/openrc/blob/master/sh/openrc-run.sh.in)
and [daemon status handling](https://github.com/OpenRC/openrc/blob/master/src/librc/librc-daemon.c).
