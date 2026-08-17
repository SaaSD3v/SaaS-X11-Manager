package com.saas.x11manager.util

/**
 * UI policy for the guided Graphic Session setup.
 *
 * This is intentionally a presentation/catalog mapping only:
 * OpenRC shows plans from the Alpine/apk catalog, while systemd shows plans
 * from the Debian/Ubuntu apt/dpkg catalog. Runtime package-manager detection
 * remains independent inside the installers.
 */
object GraphicSessionWizard {
    fun platformFor(initSystem: InitSystem): ContainerPlatform = when (initSystem) {
        InitSystem.OPENRC -> ContainerPlatform.ALPINE
        InitSystem.SYSTEMD -> ContainerPlatform.UBUNTU
    }

    fun sessionsFor(initSystem: InitSystem): List<GraphicSession> {
        val platform = platformFor(initSystem)
        return GraphicSessionSupport.installableSessions.filter { session ->
            GraphicSessionInstallPlans.forSelection(platform, session) != null
        }
    }
}
