# Credits and Acknowledgements

SaaS X11 Manager is an integration project built on top of several independent open-source projects and ecosystems. This file exists to make that lineage explicit and to give proper credit to the projects whose work makes the Manager possible.

> Inclusion here is attribution, not a claim of endorsement, sponsorship, partnership, or official affiliation. Project names and trademarks belong to their respective owners.

## Core projects

### DroidSpaces

- Website: https://www.droidspaces.org/
- Open-source repository: https://github.com/ravindu644/Droidspaces-OSS
- GitHub organization: https://github.com/Droidspaces

DroidSpaces provides the Android/Linux container runtime and container filesystem model that SaaS X11 Manager orchestrates. The Manager discovers DroidSpaces containers, controls their lifecycle, inspects runtime capabilities, prepares the X11 socket bind, accesses container root filesystems when required, and provisions graphical sessions inside those containers.

SaaS X11 Manager is not an official DroidSpaces project and does not imply endorsement by the DroidSpaces maintainers.

### Termux:X11

- Repository: https://github.com/termux/termux-x11
- Project: https://termux.dev/

Termux:X11 is the primary upstream graphics engine used by the integrated X11 architecture. The repository is included as a pinned git submodule under `third_party/termux-x11`.

The Manager reuses and adapts upstream components including the X server/native build, `LorieView`, `CmdEntryPoint`, Binder interfaces, preference definitions, extra-key infrastructure and input-related code. The local `embedded-lorie` module is a compatibility/host layer around the pinned upstream source; it is not presented as an original replacement for Termux:X11.

Termux:X11 is licensed under GNU GPL version 3. See the pinned upstream source for its complete license and notices.

### Termux

- Project: https://termux.dev/
- GitHub: https://github.com/termux

Termux is the Android terminal and Linux-environment ecosystem from which Termux:X11 originates. The integrated Manager does **not** require a separate Termux installation to own the X11 server lifecycle, but the Termux project and community remain a major upstream foundation for Termux:X11 and its Android/Linux integration work.

## X11, keyboard and native graphics foundations

### X.Org / X11

- X.Org Foundation: https://www.x.org/

The embedded server ultimately implements the X Window System protocol and builds on the X.Org/Xserver ecosystem carried by Termux:X11. Window managers and desktop environments launched inside DroidSpaces connect to the Manager-owned X display through this stack.

### xkeyboard-config / XKB

- Project: https://gitlab.freedesktop.org/xkeyboard-config/xkeyboard-config

The integrated server requires XKB keyboard configuration data. SaaS X11 Manager stages compatible XKB data from a configured container rootfs into its own runtime cache before launching the embedded server.

### FreeDesktop.org ecosystem

- Website: https://www.freedesktop.org/

A large part of the Linux graphical desktop ecosystem used by X11 sessions — specifications, XKB, D-Bus-related conventions and desktop interoperability work — comes from projects hosted or coordinated through FreeDesktop.org.

## Android application stack

### Android Open Source Project

- Website: https://source.android.com/

The Manager depends on Android platform APIs, `app_process`, the Android input system, Binder, Surface/View infrastructure, window insets and the Android native toolchain used by the embedded X11 server.

### AndroidX and Jetpack Compose

- AndroidX: https://developer.android.com/jetpack/androidx
- Jetpack Compose: https://developer.android.com/compose

The Manager UI, navigation, lifecycle integration, Material components and managed display workspace are built with AndroidX and Jetpack Compose.

### libsu

- Repository: https://github.com/topjohnwu/libsu

`libsu`, maintained by topjohnwu and contributors, provides the root-shell integration used by the Android application for privileged DroidSpaces and X11 lifecycle operations.

### Kotlin and kotlinx.coroutines

- Kotlin: https://kotlinlang.org/
- kotlinx.coroutines: https://github.com/Kotlin/kotlinx.coroutines

Kotlin is the primary application language and coroutines are used throughout asynchronous container, installer and UI operations.

## Linux userspace and init ecosystems

The Manager intentionally detects capabilities instead of assuming that a distro name implies one fixed environment. It currently integrates with and tests against major Linux userspace ecosystems including:

- Alpine Linux / `apk`: https://alpinelinux.org/
- Debian / `apt` + `dpkg`: https://www.debian.org/
- Ubuntu / `apt` + `dpkg`: https://ubuntu.com/
- OpenRC: https://github.com/OpenRC/openrc
- systemd: https://systemd.io/

These projects provide the package managers, init systems and userspaces in which the Manager installs and starts graphical sessions.

## Graphical sessions

SaaS X11 Manager can provision a range of independent X11 window managers and desktop environments when compatible packages are available in the selected container. Those projects retain their own authorship, licenses, trademarks and communities.

Examples represented by the current catalog include Openbox, IceWM, JWM, Fluxbox, cwm, i3, AwesomeWM, Ratpoison, Window Maker, bspwm, Qtile, XFCE, LXQt, LXDE, MATE, Cinnamon, Plasma X11 and other X11-capable session projects.

The Manager's session catalog is an integration layer: installing or launching a project through SaaS X11 Manager does not make that upstream project part of SaaS X11 Manager and does not imply that its maintainers endorse this application.

## Build and development tooling

The project also relies on the work of the maintainers and contributors behind:

- Gradle — https://gradle.org/
- Android Gradle Plugin and Android SDK/NDK — https://developer.android.com/
- CMake — https://cmake.org/
- JUnit — https://junit.org/
- Git and GitHub Actions — https://git-scm.com/ and https://github.com/features/actions

## Root ecosystem

The application is designed around **working root capability**, not one required root solution. Diagnostic support recognizes common Android root ecosystems such as KernelSU, APatch and Magisk. These projects are independent from SaaS X11 Manager and are not bundled as part of the Manager.

## Contributors and maintainers

Credit also belongs to the maintainers, contributors, testers and package maintainers across all of the projects above, including the many transitive libraries and Linux packages that are not practical to enumerate individually here.

When adding a new bundled upstream component, substantial copied/adapted code, or a new third-party runtime dependency, update both this file and the licensing/third-party section of `README.md` so attribution does not drift behind the implementation.

## Licensing note

This credits file is not a substitute for license compliance. The repository's `README.md` documents the current licensing situation, and bundled or embedded third-party code remains subject to its own license terms and notices.
