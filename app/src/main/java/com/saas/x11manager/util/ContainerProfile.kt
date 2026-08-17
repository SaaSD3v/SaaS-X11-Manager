package com.saas.x11manager.util

/**
 * User-selected container package/configuration profile.
 *
 * UBUNTU intentionally represents the apt-based path shared by Debian/Ubuntu
 * for the SaaS-X11-Manager UI. It is not a strict distro identity check.
 */
enum class ContainerPlatform(
    val label: String,
    val defaultInitSystem: InitSystem
) {
    UBUNTU("Ubuntu / Debian (.deb)", InitSystem.SYSTEMD),
    ALPINE("Alpine", InitSystem.OPENRC)
}

/**
 * Graphical sessions supported by the configuration model.
 * Install/start support is enabled per session as its implementation lands.
 */
enum class GraphicSession(
    val label: String,
    val startCommand: String
) {
    XFCE("XFCE", "startxfce4"),
    LXQT("LXQt", "startlxqt"),
    OPENBOX("Openbox", "openbox-session"),
    ICEWM("IceWM", "icewm-session"),
    JWM("JWM", "jwm"),
    FLUXBOX("Fluxbox", "startfluxbox"),
    CWM("cwm", "cwm"),
    HERBSTLUFTWM("herbstluftwm", "herbstluftwm"),
    SPECTRWM("spectrwm", "spectrwm"),
    I3("i3", "i3"),
    AWESOME("AwesomeWM", "awesome"),
    RATPOISON("Ratpoison", "ratpoison"),
    TWM("TWM", "twm"),
    WINDOW_MAKER("Window Maker", "wmaker"),
    FVWM("FVWM", "fvwm"),
    PEKWM("pekwm", "pekwm"),
    BLACKBOX("Blackbox", "blackbox"),
    CTWM("ctwm", "ctwm"),
    EVILWM("evilwm", "evilwm"),
    MATCHBOX("Matchbox", "matchbox-window-manager"),
    SAWFISH("Sawfish", "sawfish"),
    XMONAD("XMonad", "xmonad"),
    NINE_WM("9wm", "9wm"),
    AEWM_PLUS_PLUS("aewm++", "aewm++"),
    AFTERSTEP("AfterStep", "afterstep"),
    AMIWM("AmiWM", "amiwm"),
    DWM("dwm", "dwm"),
    FLWM("flwm", "flwm"),
    LWM("lwm", "lwm"),
    MIWM("miwm", "miwm"),
    VTWM("vtwm", "vtwm"),
    W9WM("w9wm", "w9wm"),
    NONE("None", "")
}
