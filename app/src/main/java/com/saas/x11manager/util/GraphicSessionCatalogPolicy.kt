package com.saas.x11manager.util

/**
 * Support-maturity policy for the guided graphical-session catalog.
 *
 * Repository availability and support maturity are intentionally separate:
 * a package living in a normal distro repository does not automatically make
 * the Manager integration stable. Conversely, a mature integration still
 * becomes Experimental on a platform when it requires an experimental repo.
 */
object GraphicSessionCatalogPolicy {
    private val stableSessions = setOf(
        // Desktop/session paths already exercised by the Manager's X11 pipeline.
        GraphicSession.XFCE,
        GraphicSession.LXQT,
        GraphicSession.OPENBOX,
        GraphicSession.ICEWM,
        GraphicSession.JWM,
        GraphicSession.FLUXBOX,
        GraphicSession.CWM,
        GraphicSession.HERBSTLUFTWM,
        GraphicSession.SPECTRWM,
        GraphicSession.I3,
        GraphicSession.AWESOME,
        GraphicSession.RATPOISON,
        GraphicSession.TWM,
        GraphicSession.WINDOW_MAKER,
        GraphicSession.FVWM,
        GraphicSession.DWM,
        GraphicSession.MARCO,
        GraphicSession.METACITY,
        GraphicSession.XFWM4,
        GraphicSession.ENLIGHTENMENT,
        GraphicSession.BSPWM,
        GraphicSession.QTILE,
        GraphicSession.MATE,
        GraphicSession.LXDE,

        // Wayland compositors with a direct, well-understood nested X11 route.
        GraphicSession.WESTON,
        GraphicSession.LABWC,
        GraphicSession.SWAY,
        GraphicSession.CAGE
    )

    fun isStable(plan: GraphicSessionInstallPlan): Boolean =
        plan.session in stableSessions && repositoryIsStable(plan.repositoryRequirement)

    fun isExperimental(plan: GraphicSessionInstallPlan): Boolean = !isStable(plan)

    private fun repositoryIsStable(requirement: RepositoryRequirement): Boolean =
        when (requirement) {
            RepositoryRequirement.APT_UNIVERSE,
            RepositoryRequirement.APT_MULTIVERSE,
            RepositoryRequirement.APK_COMMUNITY -> true
            RepositoryRequirement.APK_EDGE_TESTING -> false
        }
}
