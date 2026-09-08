package com.saas.x11manager.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One entry point for starting the selected graphical session.
 *
 * X11-0nly exposes the same current Start flow as X11APP, but Integrated X11 is
 * deliberately fixed to the single Manager-owned :0 / X0 transport. BOTH is
 * accepted only as a persisted compatibility value and is normalized to X11.
 */
object SessionAccessManager {
    private val startMutex = Mutex()

    suspend fun start(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        accessMode: SessionAccessMode,
        vncPort: Int,
        vncAdbLocalPort: Int = vncPort,
        vncPassword: String? = null,
        logger: ContainerLogger? = null
    ): Boolean = startMutex.withLock {
        startLocked(
            containerName = containerName,
            platform = platform,
            session = session,
            accessMode = RuntimeAccessPolicy.normalize(accessMode),
            vncPort = vncPort,
            vncAdbLocalPort = vncAdbLocalPort,
            vncPassword = vncPassword,
            logger = logger
        )
    }

    private suspend fun startLocked(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        accessMode: SessionAccessMode,
        vncPort: Int,
        vncAdbLocalPort: Int,
        vncPassword: String?,
        logger: ContainerLogger?
    ): Boolean {
        logger?.i("--- Graphic Access Start ---")
        logger?.i("[CTX] Access method: ${accessMode.label}")
        logger?.i("[CTX] Session: ${session.label}")

        // Keep the current user-facing order: session -> Linux user -> transport.
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

        if (accessMode.requiresVnc) {
            logger?.i(LogLayout.SPACER)
            logger?.i("[VNC] Preparing standalone VNC transport")
            logger?.i("[VNC] • Server port: $vncPort")
            logger?.i("[VNC] • PC local ADB port: $vncAdbLocalPort")
            logger?.i(
                if (vncPassword == null) {
                    "[VNC] • Authentication requested: none"
                } else {
                    "[VNC] • Authentication requested: VNC password"
                }
            )

            if (!ensureFixedIntegratedX11StoppedForVnc(containerName, logger)) {
                return false
            }
        }

        // Audio ownership and transport are intentionally unchanged from the
        // X11-0nly baseline. This port does not replace, refactor or migrate them.
        PulseAudioRuntimeSanitizer.prepare(
            containerName = containerName,
            logger = logger
        )
        PulseAudioFixManager.prepareBeforeGraphicalStart(
            containerName = containerName,
            logger = logger
        )

        return when (accessMode) {
            SessionAccessMode.INTEGRATED_X11,
            SessionAccessMode.BOTH -> {
                val started = X11SessionManager.startX11Session(containerName, logger) {
                    finalizeAudioAfterContainerReady(containerName, logger)
                }
                if (!started) {
                    logger?.e("[-] Integrated X11 access failed")
                    false
                } else if (!confirmManagedDesktop(containerName, session)) {
                    logger?.e("[-] ${session.label} did not become active on ${Constants.X11_DISPLAY}")
                    false
                } else {
                    logger?.i("[X11] ✓ Integrated X11 ready on Monitor 1 (${Constants.X11_DISPLAY})")
                    logger?.i("[SESSION] ✓ ${session.label} is active through Integrated X11")
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
                    logger = logger,
                    beforeGraphicSession = { finalizeAudioAfterContainerReady(containerName, logger) }
                )
                if (result.success) {
                    VncConnectionGuide.logAfterSuccessfulStart(
                        containerName = containerName,
                        port = vncPort,
                        adbLocalPort = vncAdbLocalPort,
                        displayName = result.displayName,
                        desktopUser = userPreparation.selection.userName,
                        session = session,
                        password = vncPassword,
                        logger = logger
                    )
                } else {
                    VncConnectionGuide.logAdbForwardRestartRecovery(
                        port = vncPort,
                        localPort = vncAdbLocalPort,
                        logger = logger,
                        onlyIfTroubleshooting = true
                    )
                }
                result.success
            }
        }
    }

    /**
     * X11APP can reserve another display while VNC runs. X11-0nly cannot and must
     * not grow that allocator. If this same container currently owns fixed :0,
     * stop only its managed desktop and the fixed X11 server before standalone VNC.
     * Another container's :0 session is never touched by a VNC-only start.
     */
    private suspend fun ensureFixedIntegratedX11StoppedForVnc(
        containerName: String,
        logger: ContainerLogger?
    ): Boolean {
        if (X11SessionManager.getServerStatus() != X11ServerStatus.Running) {
            logger?.i("[X11] ✓ Monitor 1 (${Constants.X11_DISPLAY}) is already stopped")
            return true
        }

        val owner = X11SessionManager.getOwnerContainerName()
        if (owner != null && owner != containerName) {
            logger?.i("[X11] • Monitor 1 (${Constants.X11_DISPLAY}) remains owned by $owner")
            return true
        }

        logger?.i("[X11] Switching this container to standalone VNC; stopping Monitor 1 (${Constants.X11_DISPLAY})")
        X11SessionManager.stopContainerGraphicSession(containerName, logger)
        val stopped = X11SessionManager.stopIntegratedServer(logger)
        if (!stopped) {
            logger?.e("[X11] ✗ Could not stop Monitor 1 (${Constants.X11_DISPLAY}) before VNC start")
            return false
        }
        logger?.i("[X11] ✓ Monitor 1 (${Constants.X11_DISPLAY}) stopped; container remains available for VNC")
        return true
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
