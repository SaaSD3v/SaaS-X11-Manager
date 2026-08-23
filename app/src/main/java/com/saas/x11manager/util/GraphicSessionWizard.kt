package com.saas.x11manager.util

/**
 * Catalog scope selected by the guided Graphic Session setup.
 *
 * STABLE only exposes sessions whose package source is part of the normal
 * supported repository path. EXPERIMENTAL is additive: it always includes the
 * stable catalog and may also expose sessions that require explicitly
 * experimental package sources.
 */
enum class GraphicSessionCatalogMode {
    STABLE,
    EXPERIMENTAL
}

/**
 * UI policy for the guided Graphic Session setup.
 *
 * Package availability belongs to the detected container package platform,
 * while init-system selection remains independent. The legacy overload is kept
 * for callers/tests that still need the old presentation mapping during the
 * migration.
 */
object GraphicSessionWizard {
    fun platformFor(initSystem: InitSystem): ContainerPlatform = when (initSystem) {
        InitSystem.OPENRC -> ContainerPlatform.ALPINE
        InitSystem.SYSTEMD -> ContainerPlatform.UBUNTU
    }

    fun sessionsFor(
        platform: ContainerPlatform,
        catalogMode: GraphicSessionCatalogMode
    ): List<GraphicSession> =
        GraphicSessionSupport.installableSessions.filter { session ->
            val plan = GraphicSessionInstallPlans.forSelection(platform, session)
                ?: return@filter false
            catalogMode == GraphicSessionCatalogMode.EXPERIMENTAL || isStable(plan)
        }

    /**
     * Keep callers that do not explicitly opt into experimental sources on the
     * stable catalog. Experimental sessions must always be an explicit wizard
     * choice.
     */
    fun sessionsFor(platform: ContainerPlatform): List<GraphicSession> =
        sessionsFor(platform, GraphicSessionCatalogMode.STABLE)

    fun sessionsFor(initSystem: InitSystem): List<GraphicSession> =
        sessionsFor(platformFor(initSystem), GraphicSessionCatalogMode.STABLE)

    fun isExperimental(
        platform: ContainerPlatform,
        session: GraphicSession
    ): Boolean {
        val plan = GraphicSessionInstallPlans.forSelection(platform, session) ?: return false
        return !isStable(plan)
    }

    private fun isStable(plan: GraphicSessionInstallPlan): Boolean =
        when (plan.repositoryRequirement) {
            RepositoryRequirement.APT_UNIVERSE,
            RepositoryRequirement.APT_MULTIVERSE,
            RepositoryRequirement.APK_COMMUNITY -> true
            RepositoryRequirement.APK_EDGE_TESTING -> false
        }
}
