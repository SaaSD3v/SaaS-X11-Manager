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

        // Audio baseline: physically validated HOST + NAT transport from 0f61eaa3.
        PulseAudioFixManager.prepareBeforeGraphicalStart(
            containerName = containerName,
            logger = logger
        )
        if (accessMode != SessionAccessMode.VNC) {
            VirGLFixManager.prepareBeforeGraphicalStart(
                containerName = containerName,
                logger = logger
            )
        }

        return when (accessMode) {
            SessionAccessMode.INTEGRATED_X11,
            SessionAccessMode.BOTH -> {
                val started = X11SessionManager.startX11Session(containerName, logger) {
                    VirGLFixManager.finalizeAfterContainerReady(containerName, logger)
                    finalizeAudioAfterContainerReady(containerName, logger)
                }
                if (!started) {
                    logger?.e("[-] Integrated X11 access failed")
                    false
                } else {
                    // startX11Session returns true only after the configured
                    // desktop handshake succeeds; do not run it a second time.
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

        val owners = X11SessionManager.getOwnerContainerNames()
        if (owners.size > 1) {
            if (containerName in owners) {
                logger?.e(
                    "[X11] ✗ Cannot detach $containerName from fixed X0 while conflicting owners remain: " +
                        owners.joinToString(", ")
                )
                return false
            }
            logger?.w(
                "[X11] • Fixed X0 has conflicting owners, but this VNC-only container does not own it: " +
                    owners.joinToString(", ")
            )
            return true
        }

        val owner = owners.singleOrNull()
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

}