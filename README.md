# SaaS X11 Manager

Android app for managing **DroidSpaces containers with Termux:X11**, with root-assisted X11 startup, container runtime control, per-container init selection, graphical-session provisioning, and live logs.

The project is being developed around one main rule: **detect capabilities at runtime instead of assuming fixed DroidSpaces, kernel, distribution, or package versions**.

## Current project status

The active development work lives on `agent/structural-fixes` and is tracked through draft PR #5.

The current architecture has already moved beyond the original XFCE-only flow:

- container discovery and runtime status use capability-based fallbacks;
- Termux:X11 is started through the app-managed root Loader path;
- container configuration is changed only where required for the X11 socket bind;
- app-owned settings are stored in a sidecar file instead of private DroidSpaces config keys;
- OpenRC and systemd are user-selected per container;
- X11 startup uses generic `x11-setup` / `x11-session` services;
- PulseAudio-specific provisioning has been removed;
- graphical sessions are modeled independently from the init system;
- Openbox is the first fully implemented `Graphic Session` workflow.

### Validation status

| Area | Status |
| --- | --- |
| Release unit tests | ✅ CI validated |
| Release APK build | ✅ CI validated |
| Container runtime fallback parsing | ✅ Unit tested |
| Manual Termux:X11 container config | ✅ Unit tested |
| OpenRC session provisioning | ✅ Unit tested |
| systemd session provisioning | ✅ Unit tested |
| Openbox + Alpine + OpenRC install flow | ✅ Tested on a real container |
| Openbox runtime Start X11 on current HEAD | ⚠️ Needs another device pass after recent race fixes |
| Openbox + apt/dpkg install flow | 🧪 Unit/CI tested; real device test still pending |

A green CI proves compilation and automated tests. It does **not** replace device/runtime validation.

## What the app does

The app discovers DroidSpaces containers and provides a native Android UI for:

- viewing container state and PID;
- starting a container together with its Termux:X11 session;
- stopping individual containers or all managed sessions;
- viewing live operation logs;
- editing per-container X11 settings;
- selecting **OpenRC** or **systemd** as the X11 init backend;
- installing, verifying, selecting and reinstalling supported graphical sessions;
- preserving installed sessions even when they are not selected.

## Architecture overview

```text
Android app
   │
   ├── DroidSpaces runtime discovery/status
   │      ├── machine-readable show capability
   │      ├── plain show capability
   │      └── per-container PID fallback
   │
   ├── Termux:X11 Loader (:0)
   │      ├── live process verification
   │      ├── X0 socket verification
   │      ├── stale socket cleanup
   │      └── owned-loader rollback
   │
   ├── DroidSpaces container.config
   │      └── minimal manual X11 socket integration
   │
   ├── .saas-x11-manager.conf
   │      ├── init_system
   │      ├── platform
   │      ├── graphic_session
   │      └── installed_<session>
   │
   └── container rootfs
          ├── /usr/local/bin/x11-session.sh
          ├── OpenRC services
          │      ├── x11-setup
          │      └── x11-session
          └── systemd units
                 ├── setup-x11-socket.service
                 └── x11-session.service
```

## DroidSpaces integration

The app currently expects the DroidSpaces installation under:

```text
/data/local/Droidspaces
```

with the runtime binary at:

```text
/data/local/Droidspaces/bin/droidspaces
```

Runtime behavior is **not selected by a hardcoded DroidSpaces version**.

Container status resolution tries available capabilities in order and only accepts output that can be parsed confidently. If one mechanism is unavailable or has an unknown format, the app falls back to the next one instead of assuming that every container is stopped.

## Manual Termux:X11 path

SaaS X11 Manager does not rely on DroidSpaces automatically owning the Termux:X11 lifecycle.

Before Start X11, the app ensures the container configuration contains the minimum manual integration required by this project:

```text
enable_termux_x11=0
```

and a bind mount from the Termux:X11 host socket directory to:

```text
/usr/.X11-unix
```

Existing unrelated `container.config` values and bind mounts are preserved.

The mutation is designed to be idempotent: running the preparation again should not keep changing the config or duplicate the X11 bind.

## Termux:X11 Loader lifecycle

The root Loader is managed for display `:0`.

An existing Loader is reused only when both conditions are true:

1. the X0 socket exists;
2. a live `termux-x11` process exists.

A socket without a live process is treated as stale and cleaned up.

When the app starts a new Loader, it tracks processes created by that operation so a failed session start can roll back only the Loader it owns.

Start X11 also waits for the container to accept a real `droidspaces run` command before considering the command channel ready. A PID alone is not treated as proof that container userspace is ready.

## Rootfs access

DroidSpaces root filesystems are not assumed to always be directories.

The app can obtain a directory view of:

- an unpacked rootfs directory;
- a filesystem image;
- a block device.

Image/block-backed root filesystems are mounted into operation-owned temporary mount points and released after use. The accessor does not pre-emptively unmount an unrelated mount belonging to another process.

## Per-container settings

SaaS X11 Manager-specific metadata lives beside `container.config` in:

```text
.saas-x11-manager.conf
```

Example:

```ini
# SaaS-X11-Manager container settings
init_system=openrc
platform=alpine
graphic_session=openbox
installed_openbox=1
```

This keeps app-specific metadata outside the DroidSpaces config format.

Persisted user choices are the source of truth. Distribution detection may suggest an initial profile, but it must not silently override an existing user choice.

## Init systems

The Edit Container screen currently supports:

- **OpenRC**
- **systemd**

The init system is a manual per-container selection.

It is intentionally independent from the package family. For example, an Alpine-based container is not forced to OpenRC merely because it uses `apk`, and a Debian-family container is not forced to systemd merely because it uses `apt/dpkg`.

### OpenRC

The generic services are:

```text
/etc/init.d/x11-setup
/etc/init.d/x11-session
```

and are enabled under the default runlevel.

`x11-setup` prepares `/tmp/.X11-unix` and binds the socket directory exposed by DroidSpaces.

`x11-session` launches:

```text
/usr/local/bin/x11-session.sh
```

### systemd

The generic units are:

```text
/etc/systemd/system/setup-x11-socket.service
/etc/systemd/system/x11-session.service
```

The setup unit prepares the X11 socket/runtime directories and the session unit launches the same generic `x11-session.sh` entry point.

Legacy XFCE-specific service names are removed during migration/provisioning.

## Graphic Session model

A **Graphic Session** is the X11 client environment started inside the container. It may be a full desktop environment or only a window manager.

The init backend and the graphical session are separate concepts:

```text
OpenRC / systemd
        │
        ▼
  x11-session.sh
        │
        ▼
 selected Graphic Session
```

A session does not need to be uninstalled when it is deselected.

The app tracks two independent states:

```text
installed
selected
```

This allows a future container to keep several graphical sessions installed and switch which one is started.

## Openbox — reference implementation

Openbox is currently the first fully enabled graphical-session workflow and is the reference contract for future sessions.

### Alpine / apk

Minimal package set:

```sh
apk add openbox
apk add xterm
apk add font-terminus
```

### Debian / Ubuntu / apt-dpkg

Minimal package set:

```sh
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openbox
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xterm
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends fonts-terminus
```

### Session command

```sh
openbox-session
```

The generated generic launcher ultimately executes:

```sh
exec openbox-session
```

### Configuration preservation

The installer creates the Openbox user config directory but does not overwrite existing user configuration.

Default `rc.xml` and `menu.xml` are copied only when the corresponding user file does not already exist.

### Install workflow

The successful Openbox model is:

```text
Detect package capability
        ↓
Resolve apk or apt/dpkg path
        ↓
Install minimal packages
        ↓
Preserve/create required user config
        ↓
Validate session command
        ↓
Provision selected init backend
        ↓
Persist package platform
        ↓
Persist init system
        ↓
Persist graphic session
        ↓
Persist installed marker
        ↓
Restore original stopped state when required
```

If an installation operation needs to start a previously stopped container, the app waits for its command channel to become ready and restores the stopped state afterward.

## Verify is intentionally non-destructive

`Verify` is not a reinstall operation.

For Openbox it validates the existing setup without performing package installation or rewriting startup files.

A verification path must not silently run operations such as:

```text
apk update
apk add
apt-get update
apt-get install
rm
cp
chmod
mkdir
service rewrites
```

It checks the package state, session command, user configuration, generic launcher and selected init backend.

## Package-platform detection

The installer resolves the package family by capability when it is not already supplied:

```text
apk
```

or:

```text
apt-get + dpkg
```

The UI/platform name `Ubuntu / Debian (.deb)` represents the shared apt/dpkg path. It is not a strict distro identity requirement.

No fixed Ubuntu, Debian or Alpine release is pinned by this design.

## Termux:X11 is the X server

Graphical-session installers should install **X11 clients/session components**, not a second X server simply because traditional desktop documentation assumes a normal PC installation.

The project therefore avoids adding components without a demonstrated need, including:

- local Xorg server stacks;
- display managers such as LightDM/SDDM/GDM;
- PulseAudio provisioning;
- unrelated desktop meta packages when a smaller session package set is sufficient.

D-Bus, Mesa, polkit, companion processes or other components should be introduced only when a specific session actually requires them.

## Planned graphical sessions

The next sessions are being researched by adapting the Openbox contract rather than duplicating Openbox-specific code.

| Session | Type | Expected command | Current state |
| --- | --- | --- | --- |
| Openbox | stacking WM | `openbox-session` | ✅ Implemented |
| IceWM | stacking WM + panel | `icewm-session` | 🔬 Researched / next candidate |
| Fluxbox | stacking WM | `startfluxbox` | 🔬 Researched |
| JWM | lightweight WM | `jwm` | 🔬 Researched |
| i3 | tiling WM | `i3` | 🔬 Researched |
| AwesomeWM | dynamic/tiling WM | `awesome` | 🔬 Researched |
| bspwm | tiling WM | custom bspwm + sxhkd launcher | 🔬 Researched |
| XFCE | desktop environment | `startxfce4` | 🧱 Model/install plans exist; installer not enabled |
| LXQt | desktop environment | `startlxqt` | 🧱 Model/install plans exist; installer not enabled |
| LXDE | desktop environment | `startlxde` / capability fallback | 🗺️ Planned |
| MATE | desktop environment | `mate-session` | 🗺️ Planned |
| Plasma X11 | desktop environment | capability-dependent | 🧪 Experimental candidate |
| Cinnamon | desktop environment | `cinnamon-session` | 🧪 Experimental candidate |
| GNOME Xorg | desktop environment | Xorg session path | 🧪 Experimental candidate |

Being listed here does **not** mean a session is supported yet.

## How new sessions should be implemented

The Openbox implementation established the behavior that future sessions should reuse:

```text
GraphicSession
   │
   ├── package/install plan
   ├── runtime command probe
   ├── optional session-specific setup
   ├── read-only verification rules
   └── start command
            │
            ▼
 generic OpenRC/systemd provisioning
            │
            ▼
 x11-session.sh
```

A new session should not require a new copy of the entire ViewModel/installer lifecycle.

The intended direction is to move Openbox-specific UI and operation state toward reusable per-session definitions while keeping any genuinely session-specific setup isolated.

Examples:

- **IceWM:** likely package + command validation with distro defaults preserved;
- **Fluxbox:** package + `startfluxbox`, preserving distro/user defaults;
- **JWM:** package + `jwm`, with configuration validation rather than hardcoded distro paths where possible;
- **i3:** session-specific config bootstrap may be required to avoid the first-run configuration wizard;
- **bspwm:** requires a companion process (`sxhkd`), so it cannot be modeled as only a single `exec bspwm` command.

## UI behavior

### Home

Each container card exposes runtime state and actions such as:

- Start X11;
- Stop;
- Logs;
- Edit container.

Runtime operations are serialized so two conflicting Start/Stop flows are not intentionally launched at the same time.

### Edit Container

The current screen separates:

1. **Init System**
2. **Graphic Session**

The Openbox card can be expanded and exposes:

- `Install` when not installed;
- `Verify` when installed;
- `Reinstall` when installed;
- selection/deselection independently from installed state.

Saving applies the selected Graphic Session together with the selected init backend. If init provisioning fails, the persisted Graphic Session selection is rolled back.

## Logging

Container and installation operations stream stdout/stderr into terminal-style dialogs.

The logger uses bounded buffers so long-running output does not grow indefinitely in memory.

Installation output is deliberately step-oriented, for example:

```text
[+] Detecting package platform
[+] OK

[+] Installing Openbox
root@container: apk add openbox
...
[+] OK

[+] Validating Openbox session command
root@container: command -v openbox-session
/usr/bin/openbox-session
[+] OK
```

## Requirements

The project currently assumes:

- Android device with working root access;
- a supported root solution capable of granting the app root shell access;
- Termux installed;
- Termux:X11 installed with its Loader assets available;
- DroidSpaces installed at the path expected by the current integration;
- at least one DroidSpaces container for container-specific operations.

The app contains root-provider detection for several common solutions, including KernelSU, APatch, Magisk, SuperSU and Lineage-style SU, but the architecture is based on **working root capability**, not a requirement for one fixed provider.

## Build

The Android project uses Gradle and JDK 17 in CI.

Debug build:

```sh
./gradlew assembleDebug
```

Release tests + build:

```sh
./gradlew testReleaseUnitTest assembleRelease
```

Release-only build:

```sh
./gradlew assembleRelease
```

## CI/CD

GitHub Actions currently runs release unit tests before the release APK build:

```sh
./gradlew testReleaseUnitTest assembleRelease
```

The resulting APK is staged and uploaded as a workflow artifact.

Manual workflow dispatch can also create a GitHub Release when release creation is explicitly requested and a non-test tag is supplied.

Optional signing uses the repository secrets:

- `RELEASE_KEYSTORE`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Development discipline

This project intentionally evolves in small validated changes.

The working rule is:

```text
one focused change
        ↓
one commit
        ↓
unit tests + release build in CI
        ↓
only after green CI: next change
```

Do not stack several unrelated structural fixes or graphical-session implementations into a single commit.

Device-only behavior must also be called out separately from CI validation.

For graphical sessions, the preferred sequence is to establish one implementation, validate it, then adapt the generic infrastructure for the next session without regressing the previous one.

## Non-goals / design constraints

The current direction deliberately avoids:

- hardcoding behavior to a specific DroidSpaces release;
- hardcoding behavior to a specific kernel version;
- assuming one init system from the distro name alone;
- modifying unrelated DroidSpaces container settings;
- installing a local X server when Termux:X11 already provides the display;
- forcing desktop meta packages when a minimal client/session set is enough;
- overwriting existing user desktop/window-manager configuration;
- treating `installed` and `selected` as the same state;
- considering CI success equivalent to real-device validation.

## License

MIT
