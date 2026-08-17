# Graphic Session Research Catalog

This file records the package/session research used by SaaS-X11-Manager. It is deliberately capability-based: no DroidSpaces, kernel, Alpine, Debian or Ubuntu version is treated as a global invariant.

## Catalog rule used by the wizard

- OpenRC selection shows sessions that currently have an Alpine/apk install plan.
- systemd selection shows sessions that currently have a Debian/Ubuntu apt/dpkg install plan.
- This is a UI/catalog convention only. OpenRC and systemd are not technically bound to a distro.
- Runtime installation still detects the package manager and validates package availability before installing.
- Termux:X11 remains the X server. Session plans must not add a display manager or a separate Xorg server just to start the selected session.

## Current catalog

The current model contains 59 installable graphical sessions. The apt catalog contains all 59 plans. The Alpine catalog currently contains 15 plans whose package path has been explicitly enabled.

### Existing cross-platform plans

- XFCE — apt core XFCE packages; Alpine `xfce4` + D-Bus/X11 helpers; launcher `startxfce4`.
- LXQt — apt `lxqt-core` + Openbox; Alpine `lxqt-desktop`; launcher `startlxqt`.
- Openbox — apt/alpine Openbox + terminal; launcher `openbox-session`.
- IceWM — apt/alpine `icewm` + `xterm`; launcher `icewm-session`.
- JWM — apt/alpine `jwm` + `xterm`; launcher `jwm`; config check `jwm -p`.
- Fluxbox — apt/alpine `fluxbox` + `xterm`; launcher `startfluxbox`.
- cwm — apt/alpine `cwm` + `xterm`; launcher `cwm`; config check `cwm -n`.
- herbstluftwm — apt/alpine `herbstluftwm` + `xterm`.
- spectrwm — apt/alpine `spectrwm` + `xterm`.
- i3 — apt `i3-wm`, Alpine `i3wm`, plus terminal; launcher `i3`; config validation uses `i3 -C`.
- AwesomeWM — apt/alpine `awesome` + `xterm`; syntax verification uses `awesome --check`.
- Ratpoison — apt/alpine `ratpoison` + `xterm`.
- TWM — apt/alpine `twm` + `xterm`.
- Window Maker — apt `wmaker`, Alpine `windowmaker`, plus terminal; launcher `wmaker`.
- MATE — apt `mate-desktop-environment-core` + `dbus-x11`; Alpine `mate-desktop-environment` + `dbus`; wrapper launches `mate-session` through a private D-Bus session.

### Apt-only researched window managers

- FVWM — `fvwm`, `xterm`; launcher `fvwm`.
- pekwm — `pekwm`, `xterm`; launcher `pekwm`.
- Blackbox — `blackbox`, `xterm`; launcher `blackbox`. No Alpine plan is enabled because the Alpine package name was not accepted as evidence for the same WM without an aarch64 package confirmation.
- ctwm — `ctwm`, `xterm`.
- evilwm — `evilwm`, `xterm`.
- Matchbox — `matchbox-window-manager`, `xterm`.
- Sawfish — `sawfish`, `xterm`.
- XMonad — `xmonad`, `xterm`.
- 9wm — `9wm`, `xterm`.
- aewm++ — `aewm++`, `xterm`.
- AfterStep — `afterstep`, `xterm`.
- AmiWM — `amiwm`, `xterm`; apt Multiverse/non-free availability is required and checked at runtime.
- dwm — `dwm`, `xterm`; Debian/Ubuntu package variants may provide `dwm.default`, so the app creates `/usr/local/bin/dwm` pointing to `dwm.default` only when a generic `dwm` executable is absent.
- flwm — `flwm`, `xterm`.
- lwm — `lwm`, `xterm`.
- miwm — `miwm`, `xterm`.
- vtwm — `vtwm`, `xterm`.
- w9wm — `w9wm`, `xterm`.
- WindowLab — `windowlab`, `xterm`.
- wm2 — `wm2`, `xterm`.
- StumpWM — `stumpwm`, `xterm`.
- Notion — `notion`, `xterm`.
- MWM — `mwm`, `xterm`.
- Marco — `marco`, `xterm`.
- Metacity — `metacity`, `xterm`.
- Xfwm4 — `xfwm4`, `xterm`.
- KWin X11 — `kwin-x11`, `dbus-x11`, `xterm`; app wrapper runs `dbus-run-session -- kwin_x11`.
- Enlightenment — `enlightenment`, `dbus-x11`, `xterm`; app wrapper runs `dbus-run-session -- enlightenment_start`.
- bspwm — `bspwm`, `sxhkd`, `xterm`; app preserves user configs, copies packaged examples only when missing, starts `sxhkd`, then execs `bspwm`.
- CLFSWM — `clfswm`, `xterm`.
- FVWM-Crystal — `fvwm-crystal`, `xterm`.
- Qtile — `qtile`, `xterm`; wrapper runs `qtile start`.
- Muffin — `muffin`, `dbus-x11`, `xterm`; private D-Bus wrapper.
- Mutter — `mutter`, `dbus-x11`, `xterm`; private D-Bus wrapper.
- UKWM — `ukwm`, `dbus-x11`, `xterm`; private D-Bus wrapper.
- Cinnamon Shell — `cinnamon`, `dbus-x11`, `xterm`; private D-Bus wrapper.
- Compiz (Ubuntu) — `compiz-core`, `compiz-plugins-default`, `dbus-x11`, `xterm`; package availability is checked before install and the label is intentionally Ubuntu-specific.
- subtle (Ubuntu) — `subtle`, `xterm`; package availability is checked before install and the label is intentionally Ubuntu-specific.

### Additional apt desktop/session plans

- LXDE — `lxde-core`, `openbox-lxde-session`, `dbus-x11`, `xterm`; wrapper launches `startlxde`.
- Plasma X11 — `plasma-workspace`, `kwin-x11`, `dbus-x11`, `xterm`; wrapper launches `startplasma-x11`.
- Cinnamon Desktop — `cinnamon-session`, `cinnamon`, `muffin`, `nemo`, `cinnamon-settings-daemon`, `dbus-x11`, `xterm`; wrapper launches `cinnamon-session`.
- Sugar — `sugar-session`, `dbus-x11`, `xterm`; wrapper launches `sugar`.
- Budgie — `budgie-session`, `budgie-core`, `dbus-x11`, `xterm`; wrapper launches `budgie-session`.
- FVWM3 — `fvwm3`, `xterm`; launcher `fvwm3`.

## Researched but intentionally not enabled yet

These are preserved here so future work does not need to rediscover why they were excluded.

- GNOME Xorg — Debian provides `gnome-session-xsession`, but current GNOME session packages install and depend on a substantial `systemd --user` session target graph. Direct root-container launch has not been proven in the current architecture.
- GNOME Classic Xorg — package exists, but it builds on the same modern GNOME/systemd-user session infrastructure; withheld for the same reason.
- GNOME Flashback — package and Metacity-based session exist, but the current package also integrates GNOME session systemd-user targets. Do not enable until direct container startup is validated.
- Unity 7 — Ubuntu arm64 packages exist, but the current session path depends on `dbus-user-session` and systemd-user integration. Not enabled for the root direct-session launcher.
- Deepin/DDE full desktop — individual DDE applications/libraries are available in some repositories, but a complete standard package/session recipe for the supported apt path has not been confirmed.
- UKUI full desktop — `ukui-session-manager`/`ukui-session` exists in some Debian suites, but a current stable generic apt recipe was not confirmed. The standalone UKWM option is separate and already enabled.
- EXWM — Debian packages exist, but a reliable app-owned Emacs configuration/launcher must be designed before enabling it. Installing the package alone is not sufficient.
- GNUstep/GWorkspace — Debian arm64 packages exist, but a complete GNUstep environment + window-manager startup wrapper is still needed before it can be represented as one graphic-session option.
- Pantheon — no standard supported apt/Alpine session recipe was confirmed for this architecture.
- NsCDE — no standard supported apt/Alpine repository recipe was confirmed.
- `icewm-experimental` — not a separate UI session; it resolves to the same IceWM launcher and should not duplicate the existing IceWM card.

## Installation safety rules

- Before Alpine installs, the generic installer performs `apk update` and `apk search -e` for each package.
- Before apt installs, it performs `apt-get update` and `apt-cache show` checks.
- Ubuntu Universe/Multiverse can be enabled only when the image identifies itself as Ubuntu and already provides `add-apt-repository`; Debian sources are not rewritten.
- No branch/version-specific Alpine repository is injected automatically.
- `--no-install-recommends` is used for generic apt session plans unless the plan explicitly says otherwise.
- User session configs must not be overwritten. App-created defaults/wrappers are installed only where the session requires them.
- Verification must not launch the selected WM/desktop.
- OpenRC/systemd service templates remain generic and only execute the selected session's `startCommand`.
