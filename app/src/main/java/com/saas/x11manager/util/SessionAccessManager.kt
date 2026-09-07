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

        // A stopped VNC container receives its normal Manager X11 bind before
        // DroidSpaces starts it. No X11 process/socket is created here: the lease
        // only makes a Stopped Monitor N visible and toggleable later from Screen.
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
        }

        PulseAudioRuntimeSanitizer.prepare(
            containerName = containerName,
            logger = logger
        )
        PulseAudioFixManager.prepareBeforeGraphicalStart(
            containerName = containerName,
            logger = logger
        )

        return when (accessMode) {
            SessionAccessMode.INTEGRATED_X11, SessionAccessMode.BOTH -> {
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
        }
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
