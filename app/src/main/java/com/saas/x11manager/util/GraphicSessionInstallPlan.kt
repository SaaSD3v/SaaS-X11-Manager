package com.saas.x11manager.util

/**
 * Repository capability that must be available before a graphical session can
 * be installed. Availability is always checked at runtime; no distro version
 * is assumed or pinned here.
 */
enum class RepositoryRequirement {
    APT_UNIVERSE,
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
        packages: List<String>
    ) = GraphicSessionInstallPlan(
        platform = ContainerPlatform.UBUNTU,
        session = session,
        repositoryRequirement = RepositoryRequirement.APT_UNIVERSE,
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
        apk(GraphicSession.HERBSTLUFTWM, listOf("herbstluftwm", "xterm"))
    )

    fun forSelection(
        platform: ContainerPlatform,
        session: GraphicSession
    ): GraphicSessionInstallPlan? =
        plans.firstOrNull { it.platform == platform && it.session == session }
}
