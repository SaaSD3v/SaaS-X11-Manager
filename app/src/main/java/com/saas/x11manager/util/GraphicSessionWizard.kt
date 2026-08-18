package com.saas.x11manager.util

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

    fun sessionsFor(platform: ContainerPlatform): List<GraphicSession> =
        GraphicSessionSupport.installableSessions.filter { session ->
            GraphicSessionInstallPlans.forSelection(platform, session) != null
        }

    fun sessionsFor(initSystem: InitSystem): List<GraphicSession> =
        sessionsFor(platformFor(initSystem))
}
