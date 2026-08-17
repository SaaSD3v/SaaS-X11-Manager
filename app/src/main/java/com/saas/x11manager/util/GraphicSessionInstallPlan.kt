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
        repositoryRequirement: RepositoryRequirement = RepositoryRequirement.APT_UNIVERSE
    ) = GraphicSessionInstallPlan(
        platform = ContainerPlatform.UBUNTU,
        session = session,
        repositoryRequirement = repositoryRequirement,
        packages = packages,
        verificationCommand = session.startCommand,
        installRecommendedPackages = false
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
                "xfce4-appfinder",
                "xfce4-panel",
                "xfce4-session",
                "xfce4-settings",
                "xfconf",
                "xfdesktop4",
                "xfwm4",
                "xfce4-terminal"
            )
        ),
        apk(GraphicSession.XFCE, listOf("dbus", "dbus-x11", "xfce4", "xfce4-terminal")),
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
        apt(GraphicSession.W9WM, listOf("w9wm", "xterm"))
    )

    fun forSelection(
        platform: ContainerPlatform,
        session: GraphicSession
    ): GraphicSessionInstallPlan? =
        plans.firstOrNull { it.platform == platform && it.session == session }
}
