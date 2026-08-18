package com.saas.x11manager.util

/**
 * Repository capability that must be available before a graphical session can
 * be installed. Availability is always checked at runtime; no distro version
 * is assumed or pinned here.
 */
enum class RepositoryRequirement {
    APT_UNIVERSE,
    APT_MULTIVERSE,
    APK_COMMUNITY
}

data class GraphicSessionInstallPlan(
    val platform: ContainerPlatform,
    val session: GraphicSession,
    val repositoryRequirement: RepositoryRequirement,
    val packages: List<String>,
    val verificationCommand: String,
    val installRecommendedPackages: Boolean
)

/**
 * Package plans intentionally avoid display managers because Termux:X11 is the
 * X server and SaaS-X11-Manager starts the selected session directly.
 */
object GraphicSessionInstallPlans {

    private fun apt(
        session: GraphicSession,
        packages: List<String>,
        repositoryRequirement: RepositoryRequirement = RepositoryRequirement.APT_UNIVERSE,
        installRecommendedPackages: Boolean = false
    ) = GraphicSessionInstallPlan(
        platform = ContainerPlatform.UBUNTU,
        session = session,
        repositoryRequirement = repositoryRequirement,
        packages = packages,
        verificationCommand = session.startCommand,
        installRecommendedPackages = installRecommendedPackages
    )

    private fun apk(
        session: GraphicSession,
        packages: List<String>
    ) = GraphicSessionInstallPlan(
        platform = ContainerPlatform.ALPINE,
        session = session,
        repositoryRequirement = RepositoryRequirement.APK_COMMUNITY,
        packages = packages,
        verificationCommand = session.startCommand,
        installRecommendedPackages = true
    )

    private val plans = listOf(
        apt(
            GraphicSession.XFCE,
            listOf(
                "dbus-x11",
                "libxfce4ui-utils",
                "thunar",
                "thunar-volman",
                "xfce4-appfinder",
                "xfce4-panel",
                "xfce4-session",
                "xfce4-settings",
                "xfconf",
                "xfdesktop4",
                "xfwm4",
                "xfce4-terminal",
                "xfce4-notifyd",
                "xfce4-power-manager"
            )
        ),
        apk(
            GraphicSession.XFCE,
            listOf("dbus", "dbus-x11", "xfce4", "xfce4-terminal", "xfce4-notifyd")
        ),
        apt(GraphicSession.LXQT, listOf("dbus-x11", "lxqt-core", "openbox")),
        apk(GraphicSession.LXQT, listOf("dbus", "dbus-x11", "lxqt-desktop")),
        apt(GraphicSession.OPENBOX, listOf("openbox", "xterm", "fonts-terminus")),
        apk(GraphicSession.OPENBOX, listOf("openbox", "xterm", "font-terminus")),
        apt(GraphicSession.ICEWM, listOf("icewm", "xterm")),
        apk(GraphicSession.ICEWM, listOf("icewm", "xterm")),
        apt(GraphicSession.JWM, listOf("jwm", "xterm")),
        apk(GraphicSession.JWM, listOf("jwm", "xterm")),
        apt(GraphicSession.FLUXBOX, listOf("fluxbox", "xterm")),
        apk(GraphicSession.FLUXBOX, listOf("fluxbox", "xterm")),
        apt(GraphicSession.CWM, listOf("cwm", "xterm")),
        apk(GraphicSession.CWM, listOf("cwm", "xterm")),
        apt(GraphicSession.HERBSTLUFTWM, listOf("herbstluftwm", "xterm")),
        apk(GraphicSession.HERBSTLUFTWM, listOf("herbstluftwm", "xterm")),
        apt(GraphicSession.SPECTRWM, listOf("spectrwm", "xterm")),
        apk(GraphicSession.SPECTRWM, listOf("spectrwm", "xterm")),
        apt(GraphicSession.I3, listOf("i3-wm", "xterm")),
        apk(GraphicSession.I3, listOf("i3wm", "xterm")),
        apt(GraphicSession.AWESOME, listOf("awesome", "xterm")),
        apk(GraphicSession.AWESOME, listOf("awesome", "xterm")),
        apt(GraphicSession.RATPOISON, listOf("ratpoison", "xterm")),
        apk(GraphicSession.RATPOISON, listOf("ratpoison", "xterm")),
        apt(GraphicSession.TWM, listOf("twm", "xterm")),
        apk(GraphicSession.TWM, listOf("twm", "xterm")),
        apt(GraphicSession.WINDOW_MAKER, listOf("wmaker", "xterm")),
        apk(GraphicSession.WINDOW_MAKER, listOf("windowmaker", "xterm")),
        apt(GraphicSession.FVWM, listOf("fvwm", "xterm")),
        apt(GraphicSession.PEKWM, listOf("pekwm", "xterm")),
        apt(GraphicSession.BLACKBOX, listOf("blackbox", "xterm")),
        apt(GraphicSession.CTWM, listOf("ctwm", "xterm")),
        apt(GraphicSession.EVILWM, listOf("evilwm", "xterm")),
        apt(GraphicSession.MATCHBOX, listOf("matchbox-window-manager", "xterm")),
        apt(GraphicSession.SAWFISH, listOf("sawfish", "xterm")),
        apt(GraphicSession.XMONAD, listOf("xmonad", "xterm")),
        apt(GraphicSession.NINE_WM, listOf("9wm", "xterm")),
        apt(GraphicSession.AEWM_PLUS_PLUS, listOf("aewm++", "xterm")),
        apt(GraphicSession.AFTERSTEP, listOf("afterstep", "xterm")),
        apt(
            GraphicSession.AMIWM,
            listOf("amiwm", "xterm"),
            RepositoryRequirement.APT_MULTIVERSE
        ),
        apt(GraphicSession.DWM, listOf("dwm", "xterm")),
        apt(GraphicSession.FLWM, listOf("flwm", "xterm")),
        apt(GraphicSession.LWM, listOf("lwm", "xterm")),
        apt(GraphicSession.MIWM, listOf("miwm", "xterm")),
        apt(GraphicSession.VTWM, listOf("vtwm", "xterm")),
        apt(GraphicSession.W9WM, listOf("w9wm", "xterm")),
        apt(GraphicSession.WINDOWLAB, listOf("windowlab", "xterm")),
        apt(GraphicSession.WM2, listOf("wm2", "xterm")),
        apt(GraphicSession.STUMPWM, listOf("stumpwm", "xterm")),
        apt(GraphicSession.NOTION, listOf("notion", "xterm")),
        apt(GraphicSession.MWM, listOf("mwm", "xterm")),
        apt(GraphicSession.MARCO, listOf("marco", "xterm")),
        apk(GraphicSession.MARCO, listOf("marco", "xterm")),
        apt(GraphicSession.METACITY, listOf("metacity", "xterm")),
        apk(GraphicSession.METACITY, listOf("metacity", "xterm")),
        apt(GraphicSession.XFWM4, listOf("xfwm4", "xterm")),
        apk(GraphicSession.XFWM4, listOf("xfwm4", "xterm")),
        apt(GraphicSession.KWIN_X11, listOf("kwin-x11", "dbus-x11", "xterm")),
        apt(GraphicSession.ENLIGHTENMENT, listOf("enlightenment", "dbus-x11", "xterm")),
        apk(GraphicSession.ENLIGHTENMENT, listOf("enlightenment", "dbus", "xterm")),
        apt(
            GraphicSession.BSPWM,
            listOf("bspwm", "sxhkd", "xterm", "rxvt-unicode", "suckless-tools")
        ),
        apt(GraphicSession.CLFSWM, listOf("clfswm", "xterm")),
        apt(GraphicSession.FVWM_CRYSTAL, listOf("fvwm-crystal", "xterm")),
        apt(GraphicSession.QTILE, listOf("qtile", "xterm")),
        apt(GraphicSession.MUFFIN, listOf("muffin", "dbus-x11", "xterm")),
        apt(GraphicSession.MUTTER, listOf("mutter", "dbus-x11", "xterm")),
        apk(GraphicSession.MUTTER, listOf("mutter", "dbus", "xterm")),
        apt(GraphicSession.UKWM, listOf("ukwm", "dbus-x11", "xterm")),
        apt(GraphicSession.CINNAMON_SHELL, listOf("cinnamon", "dbus-x11", "xterm")),
        apt(GraphicSession.COMPIZ, listOf("compiz-core", "compiz-plugins-default", "dbus-x11", "xterm")),
        apt(GraphicSession.SUBTLE, listOf("subtle", "xterm")),
        apt(GraphicSession.MATE, listOf("mate-desktop-environment", "dbus-x11")),
        apk(GraphicSession.MATE, listOf("mate-desktop-environment", "dbus")),
        apt(
            GraphicSession.LXDE,
            listOf("openbox-lxde-session", "lxpanel", "pcmanfm", "lxterminal", "dbus-x11")
        ),
        apt(
            GraphicSession.PLASMA_X11,
            listOf("plasma-desktop", "plasma-workspace", "kwin-x11", "dbus-x11", "xterm")
        ),
        apt(
            GraphicSession.CINNAMON_DESKTOP,
            listOf("cinnamon-session", "cinnamon", "muffin", "nemo", "cinnamon-settings-daemon", "dbus-x11", "xterm")
        ),
        apt(GraphicSession.SUGAR, listOf("sugar-session", "dbus-x11", "xterm")),
        apt(GraphicSession.BUDGIE, listOf("budgie-desktop", "dbus-x11", "xterm")),
        apt(GraphicSession.FVWM3, listOf("fvwm3", "xterm")),
        apk(GraphicSession.TWO_BWM, listOf("2bwm", "xterm")),
        apk(GraphicSession.BERRY, listOf("berry", "xterm")),
        apk(GraphicSession.DK, listOf("dk", "xterm")),
        apt(GraphicSession.GNOME_XORG, listOf("gnome-session", "dbus-x11", "xterm")),
        apt(GraphicSession.GNOME_FLASHBACK, listOf("gnome-session-flashback", "dbus-x11", "xterm")),
        apt(GraphicSession.GNOME_CLASSIC_XORG, listOf("gnome-shell-extensions", "dbus-x11", "xterm")),
        apt(GraphicSession.UNITY7, listOf("unity-session", "dbus-x11", "xterm")),
        apt(
            GraphicSession.UKUI_DESKTOP,
            listOf(
                "ukui-session-manager",
                "ukwm",
                "ukui-panel",
                "ukui-settings-daemon",
                "ukui-polkit",
                "ukui-menu",
                "ukui-notification-daemon",
                "ukui-sidebar",
                "peony",
                "dbus-x11",
                "xterm"
            )
        ),
        apt(GraphicSession.EXWM, listOf("elpa-exwm", "emacs-gtk", "dbus-x11", "xterm")),
        apt(GraphicSession.GNUSTEP_GWORKSPACE, listOf("gworkspace.app", "wmaker", "dbus-x11", "xterm")),
        apt(GraphicSession.NWM, listOf("nwm")),
        apt(GraphicSession.GNOME_KIOSK_X11, listOf("gnome-kiosk-script-session", "gnome-session-bin", "dbus-x11"))
    )

    fun forSelection(
        platform: ContainerPlatform,
        session: GraphicSession
    ): GraphicSessionInstallPlan? =
        plans.firstOrNull { it.platform == platform && it.session == session }
}
