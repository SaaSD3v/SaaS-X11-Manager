# Integrated X11 spike

This document applies only to the experimental branch `agent/integrated-x11-spike`.
It is not a plan to merge the experiment into `main`.

## Goal

Run the X11 server and display UI from the SaaS X11 Manager APK itself instead of
waiting for an installed `com.termux.x11` application and a Termux-side
`loader.apk`.

## Upstream engine

The branch pins `termux/termux-x11` as a git submodule at commit
`139f2197e6093d04d5df1400baa998bf1fb07b3c` and builds its `:lorie` Android
library directly into the Manager APK. The upstream project is GPL-3.0 licensed;
this spike keeps that source and its license in the submodule so licensing can be
reviewed explicitly before any future product decision.

CI checks out this upstream pin recursively so the native Xorg/Lorie submodules
used by the engine are part of the same reproducible spike build.

## Runtime model

The Manager starts the bundled `com.termux.x11.CmdEntryPoint` with Android's
`app_process`, but the classpath points to the Manager's own installed APK. The
process name is `saas-x11` and the display is `:0`.

Runtime files are owned by the Manager under:

- `/data/local/tmp/saas-x11/.X11-unix/X0`
- `/data/local/tmp/saas-x11/.X0-lock`
- `/data/local/tmp/saas-x11/server.log`
- `/data/local/tmp/saas-x11/xkb`

Lorie requires XKB keyboard data when the server starts. Instead of depending on
Termux for `/usr/share/X11/xkb`, the Manager opens a configured DroidSpaces
rootfs through the existing `RootfsAccessor`, copies its XKB data into the
Manager-owned runtime cache, then starts the bundled server with
`XKB_CONFIG_ROOT=/data/local/tmp/saas-x11/xkb`. The cache can be reused by later
sessions and by the Display tab.

DroidSpaces containers bind the Manager-owned `.X11-unix` directory to
`/usr/.X11-unix`. The DroidSpaces `enable_termux_x11` integration remains
disabled because this branch supplies the socket itself.

## UI

The main navigation contains a new `Display` tab. It can prepare/start the
integrated X11 server using XKB data from an available container, reopen the
bundled display Activity, show the server PID/socket, and stop the server.
Starting a container's X11 session also opens the same bundled Activity.

The existing post-install `Start X11` action now routes through this integrated
runtime, so finishing a graphical-session install no longer hands the user off to
an external Termux:X11 application.

## Removed runtime dependency

The main Home/Requirements refresh no longer checks whether Termux or the
Termux:X11 APK is installed. Starting an X11 session no longer launches
`com.termux.x11/.MainActivity` as an external package, waits for a `termux-x11`
process, or loads `$PREFIX/libexec/termux-x11/loader.apk`.

## First device proof

A successful Android build proves only that the embedded engine is packaged and
linked. The decisive runtime proof is still device-side:

1. install the APK from this branch;
2. make sure at least one configured container contains `/usr/share/X11/xkb`;
3. open `Display` and start the integrated server;
4. confirm `/data/local/tmp/saas-x11/.X11-unix/X0` and the XKB cache exist;
5. start a DroidSpaces container whose config was prepared by the Manager;
6. run an X11 client/session with `DISPLAY=:0`;
7. confirm it renders in the Manager-owned display Activity and accepts keyboard/touch input.

Do not remove the external-X11 implementation from other branches based only on a
successful CI build; this branch exists specifically to prove the runtime model.
