package com.saas.x11manager.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One entry point for starting the selected graphical session.
 *
 * Current runtime policy exposes exactly two independent transports: Integrated
 * X11 and standalone VNC. The old BOTH value is accepted only as a persisted
 * compatibility input and is normalized to Integrated X11 before anything runs.
 */
object SessionAccessManager {
    private val startMutex = Mutex()

    suspend fun start(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        accessMode: SessionAccessMode,
        vncPort: Int,
        vncPassword: String? = null,
        logger: ContainerLogger? = null
    ): Boolean = startMutex.withLock {
        startLocked(
            containerName = containerName,
            platform = platform,
            session = session,
            accessMode = RuntimeAccessPolicy.normalize(accessMode),
            vncPort = vncPort,
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
        vncPassword: String?,
        logger: ContainerLogger?
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

        var vncReservedSlot: X11DisplaySlot? = null
        if (accessMode == SessionAccessMode.VNC) {
            val reservation = VncX11MonitorReservation.reserveBeforeVncStart(
                containerName = containerName,
                logger = logger
            )
            if (reservation.isFailure) {
                logger?.e(
                    "[-] Could not reserve the container's X11 monitor: " +
                        (reservation.exceptionOrNull()?.message ?: "unknown error")
                )
                return false
            }
            vncReservedSlot = reservation.getOrNull()

            // VNC is a clean standalone start mode. If this container already had
            // Integrated X11 running, stop only that monitor/session while keeping
            // its lease. The user can turn it back on from Screen afterwards and
            // run VNC + Integrated X11 independently.
            if (!ensureIntegratedMonitorStoppedForVnc(containerName, vncReservedSlot, logger)) {
                return false
            }
        }

        return when (accessMode) {
            SessionAccessMode.INTEGRATED_X11, SessionAccessMode.BOTH -> {
                prepareAudioBeforeGraphicalStart(containerName, logger)
                val slot = X11SessionManager.startX11Session(
                    containerName = containerName,
                    logger = logger,
                    beforeGraphicSession = { finalizeAudioAfterContainerReady(containerName, logger) }
                )
                if (slot == null) {
                    logger?.e("[-] Integrated X11 access failed")
                    false
                } else if (!confirmManagedDesktop(containerName, session, slot)) {
                    logger?.e("[-] ${session.label} did not become active on ${slot.describe()}")
                    false
                } else {
                    logger?.i("[+] Integrated X11 ready on ${slot.describe()}")
                    true
                }
            }

            SessionAccessMode.VNC -> {
                var vncStarted = false
                try {
                    prepareAudioBeforeGraphicalStart(containerName, logger)
                    val result = VncServerManager.startStandalone(
                        containerName = containerName,
                        platform = platform,
                        session = session,
                        port = vncPort,
                        password = vncPassword,
                        logger = logger,
                        beforeGraphicSession = { finalizeAudioAfterContainerReady(containerName, logger) }
                    )
                    vncStarted = result.success
                    if (result.success) {
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
                } finally {
                    if (!vncStarted) {
                        VncX11MonitorReservation.rollbackAfterFailedVncStart(
                            containerName = containerName,
                            displaySlot = vncReservedSlot,
                            logger = logger
                        )
                    }
                }
            }
        }
    }

    private suspend fun ensureIntegratedMonitorStoppedForVnc(
        containerName: String,
        displaySlot: X11DisplaySlot?,
        logger: ContainerLogger?
    ): Boolean {
        if (displaySlot == null) return true
        if (X11SessionManager.getServerStatus(displaySlot) != X11ServerStatus.Running) {
            logger?.i("[X11] ✓ ${displaySlot.describe()} is reserved and already stopped")
            return true
        }

        logger?.i("[X11] Switching to standalone VNC; stopping active ${displaySlot.describe()}")
        X11SessionManager.stopContainerGraphicSession(containerName, logger)
        val stopped = X11SessionManager.stopIntegratedServer(displaySlot, logger)
        if (!stopped) {
            logger?.e("[X11] ✗ Could not stop ${displaySlot.describe()} before VNC start")
            return false
        }
        logger?.i("[X11] ✓ ${displaySlot.describe()} is stopped and remains reserved")
        return true
    }

    private suspend fun prepareAudioBeforeGraphicalStart(
        containerName: String,
        logger: ContainerLogger?
    ) {
        PulseAudioRuntimeSanitizer.prepare(
            containerName = containerName,
            logger = logger
        )
        PulseAudioFixManager.prepareBeforeGraphicalStart(
            containerName = containerName,
            logger = logger
        )
    }

    private suspend fun confirmManagedDesktop(
        containerName: String,
        session: GraphicSession,
        slot: X11DisplaySlot
    ): Boolean {
        if (session == GraphicSession.NONE) return true
        return X11SessionManager.ensureContainerGraphicSession(
            containerName = containerName,
            displaySlot = slot,
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
