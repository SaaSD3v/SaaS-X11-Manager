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

    fun forSelection(
        platform: ContainerPlatform,
        session: GraphicSession
    ): GraphicSessionInstallPlan? = when (platform) {
        ContainerPlatform.UBUNTU -> when (session) {
            GraphicSession.XFCE -> GraphicSessionInstallPlan(
                platform = platform,
                session = session,
                repositoryRequirement = RepositoryRequirement.APT_UNIVERSE,
                packages = listOf(
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
                ),
                verificationCommand = "startxfce4",
                installRecommendedPackages = false
            )

            GraphicSession.LXQT -> GraphicSessionInstallPlan(
                platform = platform,
                session = session,
                repositoryRequirement = RepositoryRequirement.APT_UNIVERSE,
                packages = listOf(
                    "dbus-x11",
                    "lxqt-core",
                    "openbox"
                ),
                verificationCommand = "startlxqt",
                installRecommendedPackages = false
            )

            GraphicSession.OPENBOX -> null
            GraphicSession.NONE -> null
        }

        ContainerPlatform.ALPINE -> when (session) {
            GraphicSession.XFCE -> GraphicSessionInstallPlan(
                platform = platform,
                session = session,
                repositoryRequirement = RepositoryRequirement.APK_COMMUNITY,
                packages = listOf(
                    "dbus",
                    "dbus-x11",
                    "xfce4",
                    "xfce4-terminal"
                ),
                verificationCommand = "startxfce4",
                installRecommendedPackages = true
            )

            GraphicSession.LXQT -> GraphicSessionInstallPlan(
                platform = platform,
                session = session,
                repositoryRequirement = RepositoryRequirement.APK_COMMUNITY,
                packages = listOf(
                    "dbus",
                    "dbus-x11",
                    "lxqt-desktop"
                ),
                verificationCommand = "startlxqt",
                installRecommendedPackages = true
            )

            GraphicSession.OPENBOX -> GraphicSessionInstallPlan(
                platform = platform,
                session = session,
                repositoryRequirement = RepositoryRequirement.APK_COMMUNITY,
                packages = listOf(
                    "openbox",
                    "xterm",
                    "font-terminus"
                ),
                verificationCommand = "openbox-session",
                installRecommendedPackages = true
            )

            GraphicSession.NONE -> null
        }
    }
}
