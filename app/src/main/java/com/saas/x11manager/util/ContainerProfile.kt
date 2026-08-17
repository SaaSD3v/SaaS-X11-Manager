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
    WINDOWLAB("WindowLab", "windowlab"),
    WM2("wm2", "wm2"),
    STUMPWM("StumpWM", "stumpwm"),
    NOTION("Notion", "notion"),
    MWM("MWM", "mwm"),
    MARCO("Marco", "marco"),
    METACITY("Metacity", "metacity"),
    XFWM4("Xfwm4", "xfwm4"),
    KWIN_X11("KWin X11", "saas-kwin-x11-session"),
    ENLIGHTENMENT("Enlightenment", "saas-enlightenment-session"),
    BSPWM("bspwm", "saas-bspwm-session"),
    CLFSWM("CLFSWM", "clfswm"),
    FVWM_CRYSTAL("FVWM-Crystal", "fvwm-crystal"),
    QTILE("Qtile", "saas-qtile-session"),
    MUFFIN("Muffin", "saas-muffin-session"),
    MUTTER("Mutter", "saas-mutter-session"),
    UKWM("UKWM", "saas-ukwm-session"),
    CINNAMON_SHELL("Cinnamon Shell", "saas-cinnamon-shell-session"),
    COMPIZ("Compiz (Ubuntu)", "saas-compiz-session"),
    SUBTLE("subtle (Ubuntu)", "subtle"),
    MATE("MATE", "saas-mate-session"),
    LXDE("LXDE", "saas-lxde-session"),
    PLASMA_X11("Plasma X11", "saas-plasma-x11-session"),
    CINNAMON_DESKTOP("Cinnamon Desktop", "saas-cinnamon-session"),
    SUGAR("Sugar", "saas-sugar-session"),
    BUDGIE("Budgie", "saas-budgie-session"),
    FVWM3("FVWM3", "fvwm3"),
    TWO_BWM("2bwm", "2bwm"),
    BERRY("Berry WM", "berry"),
    DK("dk", "dk"),
    GNOME_XORG("GNOME Xorg", "saas-gnome-xorg-session"),
    GNOME_FLASHBACK("GNOME Flashback", "saas-gnome-flashback-session"),
    GNOME_CLASSIC_XORG("GNOME Classic Xorg", "saas-gnome-classic-xorg-session"),
    UNITY7("Unity 7 (Ubuntu)", "saas-unity7-session"),
    UKUI_DESKTOP("UKUI Desktop", "saas-ukui-session"),
    EXWM("EXWM", "saas-exwm-session"),
    GNUSTEP_GWORKSPACE("GNUstep + GWorkspace", "saas-gnustep-gworkspace-session"),
    NWM("nwm", "nwm"),
    GNOME_KIOSK_X11("GNOME Kiosk X11", "saas-gnome-kiosk-x11-session"),
    NONE("None", "")
}
