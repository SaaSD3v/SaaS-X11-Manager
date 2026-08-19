# DroidSpaces Screen Manager

This document describes the project-owned X11 display path on the
`agent/screen-manager` branch.

The branch is based on `agent/structural-fixes`. It keeps the validated
DroidSpaces container, init-system and Graphic Session work from that branch,
then adds an integrated screen/display layer on top of it.

## Goal

Make graphical DroidSpaces sessions a first-class feature of the app instead of
requiring the user to install, open and coordinate a separate Termux:X11 APK.

The user-facing flow becomes:

```text
DroidSpaces Manager
    -> Screen
       -> configure display/input
       -> Start
       -> integrated X11 server
       -> embedded display Activity
       -> DroidSpaces container / Graphic Session
```

The container still talks standard X11 over display `:0`. It does not receive
direct control of the Android display hardware.

## Upstream X11 engine

The branch pins `termux/termux-x11` as a git submodule at commit
`139f2197e6093d04d5df1400baa998bf1fb07b3c` and builds its `:lorie` Android
library directly into the Manager APK.

The X11/Xorg/Lorie engine is therefore reused rather than reimplemented from
scratch, while lifecycle, runtime paths, configuration, UX and DroidSpaces
integration are owned by this project.

The upstream project is GPL-3.0 licensed. The source and license remain present
through the pinned submodule so the dependency and license boundary stay
explicit and reproducible.

## Runtime ownership

The integrated server no longer loads Termux's external `loader.apk` and does
not launch the installed `com.termux.x11` application.

The Manager starts the embedded `com.termux.x11.CmdEntryPoint` through Android's
`app_process`, with the Manager APK itself as the classpath.

Runtime files are project-owned under:

```text
/data/local/tmp/saas-x11/
```

Important paths:

- display: `:0`
- process name: `saas-x11`
- socket: `/data/local/tmp/saas-x11/.X11-unix/X0`
- lock: `/data/local/tmp/saas-x11/.X0-lock`
- log: `/data/local/tmp/saas-x11/server.log`
- cached XKB data: `/data/local/tmp/saas-x11/xkb`

DroidSpaces containers bind the Manager-owned X11 socket directory to the
container X11 path. The normal DroidSpaces `enable_termux_x11` integration stays
disabled because this branch provides the socket itself.

## XKB bootstrap

Lorie needs XKB keyboard data when its X server starts. The Manager does not use
Termux's prefix for that data.

On first start it selects an available DroidSpaces container, opens its rootfs
through the existing `RootfsAccessor`, and looks for XKB data under the Linux
rootfs. That data is copied into the Manager-owned cache.

After a successful seed, later starts can reuse the cache and do not require the
same container to be running.

The `Screen` page exposes the selected XKB source container so this dependency is
visible instead of being a hidden runtime assumption.

## Main menu: Screen

The main navigation is:

```text
Home | Screen | Requirements
```

`Screen` is the dedicated control surface for the integrated X11 display.

The top runtime card shows:

- X11 server state;
- display number;
- socket path;
- server PID;
- XKB source container;
- `Start` / `Open`;
- `Stop`.

When the server is stopped, `Start` applies the saved configuration, starts the
embedded server, waits for the X11 socket and opens the embedded display
Activity.

When the server is already running, the same primary action becomes `Open` and
brings the display Activity back to the foreground without restarting the
server.

## Screen configuration

The UI exposes only settings that map to preference keys supported by the pinned
Lorie engine.

### Output

- resolution mode: Native, Scaled, Exact or Custom;
- scale: 30% to 300%;
- exact resolution presets;
- custom `widthxheight` resolution;
- Nearest or Bilinear filtering;
- orientation;
- fullscreen;
- exact/custom stretching;
- cutout avoidance.

### Input and Android integration

- Trackpad, Simulated Touch or Direct Touch mode;
- clipboard synchronization;
- additional keyboard bar;
- keep-screen-awake behavior.

The app persists these choices in its own `screen_manager` SharedPreferences.
They are independent from per-container `.saas-x11-manager.conf` settings.

## Preference bridge

`ScreenManager` is the project-owned control layer between the Compose UI and
the embedded Lorie engine.

It translates the app model into Lorie's real preference contract, including:

- `displayResolutionMode`
- `displayScale`
- `displayResolutionExact`
- `displayResolutionCustom`
- `displayFilteringMode`
- `displayStretch`
- `fullscreen`
- `forceOrientation`
- `hideCutout`
- `clipboardEnable`
- `touchMode`
- `screenIdleTimeout`
- `showAdditionalKbd`

Updates are sent directly to the embedded Lorie preference receiver in the same
APK. The Screen UI therefore does not maintain a second fake set of display
options.

## Separation of responsibilities

The branch intentionally keeps three layers separate:

```text
ScreenScreen
    UI and user interaction

ScreenManager
    persistence + Lorie preference bridge + high-level Start/Open/Stop

X11SessionManager
    X server process + socket + XKB staging + container session lifecycle
```

This prevents the Home screen or container configuration code from becoming the
owner of Android display preferences.

## Relationship with Graphic Sessions

Graphic Sessions remain container-side components:

```text
Openbox / XFCE / Qtile / ...
    -> DISPLAY=:0
    -> DroidSpaces X11 socket bind
    -> integrated Screen server
    -> Android display Activity
```

The Screen manager is not a desktop environment and is not tied to a specific
session. The same server can display any supported X11 Graphic Session.

## External Termux:X11 dependency

For this integrated path, the external Termux:X11 APK is not required at
runtime.

The embedded engine still originates from the Termux:X11/Lorie open-source
project, but its code is built into this APK and its server is launched from the
Manager-owned runtime.

This distinction is important:

```text
No external Termux:X11 APK dependency
!=
No Termux:X11/Lorie source dependency
```

## CI

The branch CI initializes the pinned X11 source explicitly instead of hiding the
entire recursive submodule operation inside `actions/checkout`.

Submodule initialization uses parallel jobs and retry logic before the Gradle
build. The workflow then runs:

```text
testReleaseUnitTest
assembleRelease
```

Unit tests include the integrated server command/runtime contract and the Screen
to-Lorie preference contract.

A successful CI build proves that the embedded source compiles with the Android
app and that the automated contracts pass. It does not replace device-side X11
render/input validation.

## Device validation checklist

The decisive runtime test is:

1. install the APK built from `agent/screen-manager`;
2. ensure at least one configured container contains XKB data for the first
   bootstrap;
3. open `Screen`;
4. select/configure resolution and input options;
5. tap `Start`;
6. confirm `/data/local/tmp/saas-x11/.X11-unix/X0` exists;
7. start a DroidSpaces Graphic Session;
8. confirm its X11 client connects to `DISPLAY=:0`;
9. confirm the desktop renders in the embedded display Activity;
10. validate touch, keyboard, orientation, clipboard and resolution changes;
11. stop the Screen server and confirm only the Manager-owned X11 process/socket
    are cleaned up.

Do not treat CI alone as proof of final runtime behavior. The branch is structured
so build validation and real-device validation can be performed independently.
