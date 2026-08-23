package com.saas.x11manager.util

enum class GraphicSessionCatalogMode {
    STABLE,
    EXPERIMENTAL
}

/**
 * UI policy for the guided Graphic Session setup.
 *
 * Package availability belongs to the detected container package platform,
 * while init-system and graphical-protocol selection remain independent. The
 * capability-aware overload also filters distro/architecture constraints.
 *
 * Stable and Experimental are intentionally exclusive catalogs. Selecting
 * Experimental must never repeat entries already visible in Stable.
 */
object GraphicSessionWizard {
    fun platformFor(initSystem: InitSystem): ContainerPlatform = when (initSystem) {
        InitSystem.OPENRC -> ContainerPlatform.ALPINE
        InitSystem.SYSTEMD -> ContainerPlatform.UBUNTU
    }

    fun sessionsFor(
        capabilities: ContainerCapabilities,
        catalogMode: GraphicSessionCatalogMode,
        protocol: GraphicProtocol
    ): List<GraphicSession> =
        GraphicSessionRegistry.installableSessions.filter { session ->
            if (session.protocol != protocol) return@filter false
            val platform = capabilities.platform ?: return@filter false
            val plan = GraphicSessionInstallPlans.forSelection(platform, session)
                ?: return@filter false
            if (!plan.supports(capabilities)) return@filter false
            belongsToCatalog(plan, catalogMode)
        }

    fun sessionsFor(
        platform: ContainerPlatform,
        catalogMode: GraphicSessionCatalogMode,
        protocol: GraphicProtocol
    ): List<GraphicSession> =
        GraphicSessionRegistry.installableSessions.filter { session ->
            if (session.protocol != protocol) return@filter false
            val plan = GraphicSessionInstallPlans.forSelection(platform, session)
                ?: return@filter false
            belongsToCatalog(plan, catalogMode)
        }

    fun sessionsFor(
        platform: ContainerPlatform,
        catalogMode: GraphicSessionCatalogMode
    ): List<GraphicSession> =
        sessionsFor(platform, catalogMode, GraphicProtocol.X11)

    /**
     * Keep callers that do not explicitly opt into experimental sources on the
     * stable X11 catalog. Experimental and Wayland sessions must always be an
     * explicit wizard choice.
     */
    fun sessionsFor(platform: ContainerPlatform): List<GraphicSession> =
        sessionsFor(platform, GraphicSessionCatalogMode.STABLE, GraphicProtocol.X11)

    fun sessionsFor(initSystem: InitSystem): List<GraphicSession> =
        sessionsFor(
            platformFor(initSystem),
            GraphicSessionCatalogMode.STABLE,
            GraphicProtocol.X11
        )

    fun isExperimental(
        capabilities: ContainerCapabilities,
        session: GraphicSession
    ): Boolean {
        val platform = capabilities.platform ?: return false
        val plan = GraphicSessionInstallPlans.forSelection(platform, session) ?: return false
        if (!plan.supports(capabilities)) return false
        return GraphicSessionCatalogPolicy.isExperimental(plan)
    }

    fun isExperimental(
        platform: ContainerPlatform,
        session: GraphicSession
    ): Boolean {
        val plan = GraphicSessionInstallPlans.forSelection(platform, session) ?: return false
        return GraphicSessionCatalogPolicy.isExperimental(plan)
    }

    private fun belongsToCatalog(
        plan: GraphicSessionInstallPlan,
        catalogMode: GraphicSessionCatalogMode
    ): Boolean = when (catalogMode) {
        GraphicSessionCatalogMode.STABLE -> GraphicSessionCatalogPolicy.isStable(plan)
        GraphicSessionCatalogMode.EXPERIMENTAL -> GraphicSessionCatalogPolicy.isExperimental(plan)
    }
}
