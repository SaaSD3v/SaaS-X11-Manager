# X11-0nly review — 2026-09-10

This variant embeds Lorie in the APK and deliberately owns one X11 server on `:0`,
with the socket `/data/local/tmp/saas-x11/.X11-unix/X0`. The renderer, runtime
constants, container bind, init templates and standalone VNC adapter were reviewed
before adapting the current X11APP changes. They retain this fixed transport.

## Behavior

- The single display card fills the available width. The screen exposes Start,
  Stop, logs, configuration and fullscreen without controls for creating or deleting
  displays that this runtime does not support.
- Screen operations live in an application-owned ViewModel. Navigation and Activity
  recreation preserve the operation. A minimized operation receives a foreground
  notification; completion removes the progress indicator and enables Close.
- Home, installation/verification and display logs share private persistent storage.
  Completed inline progress cards disappear, while logs remain available through
  their normal screen controls and notification. Restoring a process never replays
  commands; an unconfirmed operation is recorded as interrupted.
- Starting the display again resumes its running owner's configured desktop.
  Stopping the display leaves the container running. Home Stop retains its separate
  container-stop behavior.
- Process/socket mutations are serialized. Audio and graphical handshakes remain
  outside the server lock, allowing the existing bounded recovery to restart X0.
- Only the exact Manager socket bind establishes ownership. A foreign X11 bind
  cannot block or stop X0. Stopping one container preserves any remaining live owner.
- Standalone VNC retains its own virtual display and selected Linux user. It does
  not introduce another Manager X11 transport.

## Shared appearance and operations

The main screens and X11, general, TigerVNC and audio editors use the same theme
roles, section cards and controls. Light and ordinary dark remain distinct from
optional AMOLED. Android 12+ dynamic colors are optional; static palettes do not
inherit the wallpaper colors. Native system bars follow the selected editor theme.
The resolution menu has an outline so it remains distinct on a black background.

Installation package choices now belong to the operation lifetime, so closing a
screen cannot clear an active Alpine or Debian/Ubuntu installation choice. Shared
installation overrides are serialized while an operation is minimized.

## Audio adaptation

The older ONLY code lacked two corrections in the user-confirmed working X11APP:

1. Long shell payloads exceeded the DroidSpaces daemon's argument limit. Commands
   now travel as bounded arguments and are reconstructed exactly inside the container.
2. Cookie serialization and the command exit marker could corrupt the transmitted
   authentication bytes. The shared encoder validates the complete 256-byte cookie;
   the exit marker is separated from output even when a command ends without a newline.

HOST keeps authenticated loopback TCP; NAT keeps `PulseAudioNatScriptTransport`
and its gateway/listener selection. Both use the corrected command and cookie
adapters, persistent root/desktop-user client settings, authentication checks and
PCM drain verification. A failed setup identifies the failing stage. Audio setup
does not restart the container or X11 server.

## Validation scope

`x11-only-ci.yml` runs the fixed-runtime source contract, unit tests and five release
APK builds. Audio regressions execute the production payload through the pinned
upstream DroidSpaces request parser and a real PulseAudio daemon, checking playback,
cookie preservation, authentication rejection and failure reporting. The existing
OpenRC/Alpine and systemd session tests remain specific to X0.

`appearance-tests.yml` runs the production Android controls on API 26, 31, 32 and 34:
48 palette/theme combinations per system, actual editor windows, menus, keyboard,
large text, persistence and the fixed display workspace. Reports and screenshots
are attached to each workflow run. API 32 and 34 also exercise real wallpaper color
extraction; API 31's emulator does not implement that extraction.

These automated audio checks validate the command/client transport. They do not
claim physical speaker playback or a rooted Android NAT namespace test for this
new ONLY APK; the earlier hardware confirmation belongs to the X11APP baseline.
