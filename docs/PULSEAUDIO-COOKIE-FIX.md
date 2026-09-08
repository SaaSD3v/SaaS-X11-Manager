# PulseAudio client failure — 2026-09-08

Scope: audio in `X11APP`, based on the user's latest branch. No monitor,
container, desktop, VNC or interface behavior is changed by this correction.

The reported log showed a working AAudio core and a loaded HOST listener, followed
by `Container audio client configuration failed`. The failure was reproduced in
the production HOST cookie encoder: an AWK string was escaped as if it were inside
a regular Kotlin string, although it was inside a raw triple-quoted string.
AWK consequently emitted **two** backslashes before every octal byte. The
container's POSIX `printf %b` decoded those escapes into 1,280 literal characters
instead of the original 256-byte cookie, and the HOST payload exited with code 63.
It never reached client authentication. The NAT encoder used a regular Kotlin
string and did not have that particular extra escaping layer.

There was a second independent HOST bug: the Termux executor printed
`__SAAS_DIRECT_RC__0` immediately after stdout without starting a new line. Since
the encoder intentionally has no trailing newline, its status marker became part
of the serialized cookie and was not removed by the line-based result parser.
The executor now always puts its status marker on its own line. The regression
executes the production command envelope too; fixing AWK alone still failed it.

The reference supplied by the user was
`SaaS-DroidSpaces-Audio-Auto-v3.2.0-HOST-NAT-RELEASE.sh`, SHA-256
`9b19d979b4bd83953686d239b688afb847f00b8b45ed2703605d393ea4fc5c39`.
Its `od` → AWK → ASCII octal → container `printf %b` sequence is retained.
The encoder is now shared by the existing audio paths and validates the complete
serialized format before returning credentials. Cookie contents are never logged.

Previously, shell tests supplied a correctly encoded cookie themselves, so they
bypassed the broken production encoder and command envelope. They now execute the encoder. New tests
cover all byte values, quoted paths, truncated files, the exact old double-escape
failure, and stage-specific failures that survive the existing concise log filter.
Client setup also explicitly checks `pacat`, which its PCM verification requires.

CI installs Linux PulseAudio and ALSA clients and requires the real transport
fixture. It launches one isolated daemon with authenticated listeners on two
loopback addresses, then runs the generated HOST → NAT → HOST client payloads.
It checks the exact cookie, persistent endpoint changes, real `pactl`
authentication, finite `pacat` and ALSA playback, and rejection of a wrong cookie.
Only root/container namespace entry is represented by a fixture; libpulse,
PulseAudio TCP and ALSA are real. Linux's
[null sink](https://www.freedesktop.org/wiki/Software/PulseAudio/Documentation/User/Modules/)
replaces the Android device in this test. This checks the actual transport and
configuration, but does not claim audible Android hardware or real NAT namespace
validation. The Android core, listener routing and desktop lifecycle are preserved.

Run the real transport fixture with the Linux packages installed:

```sh
SAAS_PULSE_INTEGRATION_REQUIRED=1 ./gradlew testReleaseUnitTest
```
