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

## Follow-up: the shell works but the APK command never starts

The next device log still reported `container command (exit 1)`, with no marker
from the payload's initial EXIT trap. The previous cookie corrections were
necessary, but the successful Linux PulseAudio tests did not cover DroidSpaces'
daemon request parser: their entry fixture simply executed the supplied argv.

The original upstream
[daemon request parser](https://github.com/ravindu644/Droidspaces-OSS/blob/f60294ac4c8aa2cc860773ac85bc5790104eb77f/src/daemon.c)
rejects an argument larger than 8,192 bytes before running any container command.
Executing its actual `recv_req()` reproduced the difference:

| Generated HOST script / argument | UTF-8 bytes | Parser result |
| --- | ---: | --- |
| User's v3.2.0 reference, default test disabled | 6,091 | Accepted |
| APK HOST client setup | 10,442 | Rejected, exit 1 |
| APK NAT client setup | 11,837 | Rejected, exit 1 |

The payloads were measured with a 256-byte cookie and `tcp:127.0.0.1:4713`.
The daemon returns `daemon: bad request`; the concise UI previously retained only
the generic audio failure. That diagnostic now has an audio-specific summary.

`PulseAudioContainerCommand` sends the same script as small, separately quoted
argv words. A short `/bin/sh -c` bootstrap concatenates them **inside the
container**, then invokes the existing `/bin/sh -lc` session with the original
script. It preserves newlines, quotes, octal escapes and Unicode characters.
Each word is at most 3 KiB of UTF-8, and the full command stays within the daemon's
argument-count limit. No stdin forwarding, temporary script file, direct
namespace entry, daemon bypass, or changes to the Android audio core are needed.

CI now compiles `app/src/test/native/droidspaces_audio_request.c` against the
pinned upstream `daemon.c` and `droidspace.h`, verifying both source hashes. The
fixture serializes a real daemon request and calls the original `recv_req()`;
only namespace entry is replaced with local execution. It first proves that the
old large arguments are rejected, then checks exact reconstruction of a large
quoted Unicode script. The existing HOST → NAT → HOST PulseAudio/ALSA test now
passes through this parser and the production command builder as well.

For local runs, set `SAAS_DROIDSPACES_AUDIO_FIXTURE` to the compiled native fixture
alongside `SAAS_PULSE_INTEGRATION_REQUIRED=1`. CI requires both. This closes the
missing command-entry coverage; Android hardware audibility still requires an
on-device test of the generated APK.
