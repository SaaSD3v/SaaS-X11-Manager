# Audio transport baseline

Status: **physically validated on real hardware**

Date: **2026-09-05**

Active development branch: **`X11APP`**

This document records the audio architecture that must be treated as the current working baseline for SaaS X11 Manager. It exists to prevent a future cleanup or refactor from reintroducing the experimental NAT layers that were removed before the successful device validation.

## Shared host audio core

HOST and NAT use the same Manager-owned PulseAudio core on Android/Termux. The core exposes a private UNIX control socket owned by the Manager and uses an Android sink:

```text
Manager-owned PulseAudio
        |
        +-- private UNIX control socket
        |
        +-- AAudio_sink or OpenSL_ES_sink
        |
        +-- Android audio output
```

DroidSpaces native PulseAudio is disabled for future starts while the Manager integration is enabled. Audio setup must not start, stop, restart, or otherwise own the DroidSpaces container, X11 server, VNC server, or desktop lifecycle.

## HOST baseline

HOST uses the already validated `PulseAudioUnifiedTransport` path.

The active HOST path must remain independent from the NAT implementation unless a future real-device validation proves a replacement.

## NAT baseline

NAT uses **only** `PulseAudioNatScriptTransport`.

The implementation is intentionally derived from the physically validated `SaaS-DroidSpaces-Audio-Auto v3.2.0 HOST+NAT` shell behavior and has also been physically validated end-to-end from the APK on a real NAT container.

The validated path is:

```text
DroidSpaces NAT container
        |
        | authenticated PulseAudio TCP client
        v
Android-side DroidSpaces NAT gateway
        |
        | exact-address TCP listener
        v
Manager-owned PulseAudio core
        |
        v
AAudio_sink / OpenSL_ES_sink
        |
        v
Android audio output
```

On the validated DroidSpaces topology, `172.28.0.1` is the verified Android-side NAT gateway fallback. The implementation still resolves the live default gateway first and only uses the verified fallback when appropriate.

## NAT invariants

The following are part of the validated behavior and should not be changed casually:

- bind only to the exact Android-side NAT endpoint;
- never bind the audio listener to `0.0.0.0`;
- never enable anonymous PulseAudio authentication;
- always use the Manager-owned 256-byte PulseAudio cookie;
- use the same `PULSE_SERVER`, `PULSE_COOKIE`, and `PULSE_CLIENTCONFIG` environment contract for host-side `pactl` calls;
- try ports **4713 through 4777** automatically;
- skip ports reserved by DroidSpaces TCP port-forward configuration;
- probe an existing authenticated listener before creating another one;
- when a newly created module fails verification, unload only the exact module ID returned by that load attempt;
- do not scan all Android `/proc/<pid>/fd` tables to discover socket ownership;
- do not introduce a separate NAT host-readiness owner scanner;
- do not introduce a second NAT preflight/finalizer that loads the listener again;
- do not change global sysctls such as `ip_nonlocal_bind`;
- do not add firewall, NAT, or port-forward rules for container-to-host audio;
- do not restart the container or graphical session as part of audio finalization.

## Container-side proof

A NAT start is considered transport-ready only when the real running container can reach the Manager listener and `pactl info` reports the Android sink.

The expected proof is equivalent to:

```text
Server String: tcp:<android-nat-gateway>:<selected-port>
Default Sink: AAudio_sink
```

or the supported OpenSL ES fallback:

```text
Default Sink: OpenSL_ES_sink
```

The implementation also installs/writes the persistent container client configuration and exact 256-byte cookie when needed.

## Retired NAT experiments

These layers are **not** part of the working baseline and must remain retired:

```text
PulseAudioNatHostReadiness
PulseAudioNatPreflight
```

The global `/proc/[0-9]*/fd/*` ownership scan that was previously used by NAT diagnostics caused a real-device freeze on an older Android kernel and must not return.

## Validation rule

CI proves source/test/build integrity. It does not replace real-device validation.

For the current baseline, both conditions have been satisfied:

1. the clean NAT implementation passed the Android project CI; and
2. the user physically confirmed real audible NAT audio from the APK on the target device.

Any future transport redesign should therefore be treated as a regression risk until it passes both CI and the same end-to-end device proof.
