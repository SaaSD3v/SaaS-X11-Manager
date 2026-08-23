package com.saas.x11manager.util

/**
 * Experimental Wayland package plans kept separate from the mature catalog.
 *
 * Every plan is still capability-driven at install time: the package installer
 * checks the real repository candidate before changing the container. A plan
 * only describes a known packaging route, it never assumes a distro release.
 */
object AdditionalWaylandInstallPlans {
    private val debFamily = setOf(ContainerDistribution.DEBIAN, ContainerDistribution.UBUNTU)
    private val alpineOnly = setOf(ContainerDistribution.ALPINE)
    private val arm64 = setOf("arm64", "aarch64")

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
        installRecommendedPackages = false,
        supportedDistributions = debFamily,
        supportedArchitectures = arm64
    )

    private fun apk(
        session: GraphicSession,
        packages: List<String>,
        repositoryRequirement: RepositoryRequirement = RepositoryRequirement.APK_COMMUNITY,
        repositoryPackages: Set<String> = emptySet()
    ) = GraphicSessionInstallPlan(
        platform = ContainerPlatform.ALPINE,
        session = session,
        repositoryRequirement = repositoryRequirement,
        packages = packages,
        verificationCommand = session.startCommand,
        installRecommendedPackages = true,
        repositoryPackages = repositoryPackages,
        supportedDistributions = alpineOnly,
        supportedArchitectures = arm64
    )

    private val plans = listOf(
        // Debian 13 and current Ubuntu repositories ship wlmaker on arm64.
        apt(
            GraphicSession.WLMAKER,
            listOf("wlmaker", "xwayland", "foot")
        ),

        // River is a strong Alpine option. On newer Alpine branches the package
        // may be supplied by river-classic; apk resolves the virtual `river`
        // provider while the executable remains `river`.
        apk(
            GraphicSession.RIVER,
            listOf("river", "xwayland", "foot")
        ),

        // Phoc explicitly honors WLR_BACKENDS/WLR_RENDERER and works standalone.
        apt(
            GraphicSession.PHOC,
            listOf("phoc", "xwayland", "foot")
        ),

        // Mutter is offered only where the installed executable advertises a
        // supported nested development mode (--devkit or legacy --nested).
        apt(
            GraphicSession.MUTTER_WAYLAND,
            listOf("mutter", "dbus-x11", "foot")
        ),

        // Alpine's Qtile package carrying the Wayland backend is currently an
        // edge/testing route. Post-install verification additionally imports the
        // backend so a Python-only X11 build is rejected cleanly.
        apk(
            GraphicSession.QTILE_WAYLAND,
            listOf("qtile", "foot", "xwayland"),
            repositoryRequirement = RepositoryRequirement.APK_EDGE_TESTING,
            repositoryPackages = setOf("qtile")
        )
    )

    fun forSelection(
        platform: ContainerPlatform,
        session: GraphicSession
    ): GraphicSessionInstallPlan? = plans
        .firstOrNull { it.platform == platform && it.session == session }
        ?.let(AptInstallRecommendationOverride::apply)
        ?.let(AlpineInstallProfileOverride::apply)
}

/** Central plan lookup so the original X11/Wayland plans stay backward-compatible. */
object GraphicSessionPlanResolver {
    fun forSelection(
        platform: ContainerPlatform,
        session: GraphicSession
    ): GraphicSessionInstallPlan? =
        GraphicSessionInstallPlans.forSelection(platform, session)
            ?: AdditionalWaylandInstallPlans.forSelection(platform, session)
}
