package com.saas.x11manager.util

/**
 * One entry point for starting the selected graphical session through the user's
 * preferred access method. Integrated X11 is always display :0 on X11-0nly.
 *
 * BOTH starts the single Integrated X11 session first and publishes that exact
 * :0 display with x0vncserver. It never starts a second desktop/WM instance.
 */
object SessionAccessManager {
    suspend fun start(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        accessMode: SessionAccessMode,
        vncPort: Int,
        vncPassword: String? = null,
        logger: ContainerLogger? = null
    ): Boolean {
        logger?.i("--- Graphic Access Start ---")
        logger?.i("[CTX] Access method: ${accessMode.label}")
        logger?.i("[CTX] Session: ${session.label}")
        if (accessMode.requiresVnc) logger?.i("[CTX] VNC port: $vncPort")

        val userPreparation = GraphicSessionUserManager.prepareForStart(
            containerName = containerName,
            session = session,
            logger = logger
        ) ?: return false

        if (
            userPreparation.changed &&
            accessMode != SessionAccessMode.VNC &&
            ContainerManager.getContainerInfo(containerName)?.isRunning == true
        ) {
            logger?.i("[*] Graphical user changed; restarting only the managed desktop session")
            X11SessionManager.stopContainerGraphicSession(containerName, logger)
        }
        logger?.i("")

        PulseAudioRuntimeSanitizer.prepare(
            containerName = containerName,
            logger = logger
        )
        PulseAudioFixManager.prepareBeforeGraphicalStart(
            containerName = containerName,
            logger = logger
        )

        return when (accessMode) {
            SessionAccessMode.INTEGRATED_X11 -> {
                val started = X11SessionManager.startX11Session(containerName, logger)
                if (!started) {
                    logger?.e("[-] Integrated X11 access failed")
                    false
                } else if (!confirmManagedDesktop(containerName, session)) {
                    logger?.e("[-] ${session.label} did not become active on ${Constants.X11_DISPLAY}")
                    false
                } else {
                    finalizeAudioAfterContainerReady(containerName, logger)
                    logger?.i("[+] Integrated X11 ready on ${Constants.X11_DISPLAY}")
                    true
                }
            }

            SessionAccessMode.VNC -> {
                val result = VncServerManager.startStandalone(
                    containerName = containerName,
                    platform = platform,
                    session = session,
                    port = vncPort,
                    password = vncPassword,
                    logger = logger
                )
                if (result.success) {
                    finalizeAudioAfterContainerReady(containerName, logger)
                    VncConnectionGuide.logAfterSuccessfulStart(
                        containerName = containerName,
                        port = vncPort,
                        password = vncPassword,
                        logger = logger
                    )
                } else {
                    VncConnectionGuide.logAdbForwardRestartRecovery(
                        port = vncPort,
                        logger = logger,
                        onlyIfTroubleshooting = true
                    )
                }
                result.success
            }

            SessionAccessMode.BOTH -> {
                val started = X11SessionManager.startX11Session(containerName, logger)
                if (!started) {
                    logger?.e("[-] Integrated X11 could not start; VNC mirror was not attempted")
                    false
                } else if (!confirmManagedDesktop(containerName, session)) {
                    logger?.e("[-] ${session.label} did not become active on ${Constants.X11_DISPLAY}; VNC mirror was not attempted")
                    false
                } else {
                    finalizeAudioAfterContainerReady(containerName, logger)
                    val mirror = VncServerManager.startMirror(
                        containerName = containerName,
                        platform = platform,
                        session = session,
                        integratedDisplayName = Constants.X11_DISPLAY,
                        port = vncPort,
                        password = vncPassword,
                        logger = logger
                    )
                    if (!mirror.success) {
                        logger?.w("[!] VNC mirror failed, but Integrated X11 remains available on ${Constants.X11_DISPLAY}")
                        VncConnectionGuide.logAdbForwardRestartRecovery(
                            port = vncPort,
                            logger = logger,
                            onlyIfTroubleshooting = true
                        )
                        false
                    } else {
                        logger?.i("[+] Integrated X11 and VNC are sharing ${Constants.X11_DISPLAY}")
                        VncConnectionGuide.logAfterSuccessfulStart(
                            containerName = containerName,
                            port = vncPort,
                            password = vncPassword,
                            logger = logger
                        )
                        true
                    }
                }
            }
        }
    }

    private suspend fun confirmManagedDesktop(
        containerName: String,
        session: GraphicSession
    ): Boolean {
        if (session == GraphicSession.NONE) return true
        return X11SessionManager.ensureContainerGraphicSession(
            containerName = containerName,
            logger = null
        )
    }

    private suspend fun finalizeAudioAfterContainerReady(
        containerName: String,
        logger: ContainerLogger?
    ) {
        val mode = ContainerManager.getContainerInfo(containerName)
            ?.netMode
            ?.trim()
            ?.lowercase()

        if (mode == "nat") {
            PulseAudioNatScriptTransport.finalizeAfterContainerReady(
                containerName = containerName,
                logger = logger
            )
        } else {
            PulseAudioUnifiedTransport.finalizeAfterContainerReady(
                containerName = containerName,
                logger = logger
            )
        }
    }
}
