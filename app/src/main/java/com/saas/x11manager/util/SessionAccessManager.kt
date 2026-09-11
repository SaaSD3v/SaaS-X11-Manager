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
        val perf = RuntimePerfTrace("graphic-start:${accessMode.name.lowercase()}", containerName)
        var completed = false
        try {
            logger?.i("--- Graphic Access Start ---")
            logger?.i("[CTX] Access method: ${accessMode.label}")
            logger?.i("[CTX] Session: ${session.label}")

            // Resolve and apply the selected Linux account before transport-specific
            // output so the terminal reads in user-facing order: session -> user ->
            // transport -> monitor/runtime.
            val userPreparation = perf.stage("user.prepare") {
                GraphicSessionUserManager.prepareForStart(
                    containerName = containerName,
                    session = session,
                    logger = logger
                )
            } ?: return false

            if (
                userPreparation.changed &&
                accessMode != SessionAccessMode.VNC &&
                perf.stage("user.runtime-state") {
                    ContainerManager.getContainerInfo(containerName)?.isRunning == true
                }
            ) {
                logger?.i("[*] Graphical user changed; restarting only the managed desktop session")
                perf.stage("desktop.stop-old-user") {
                    X11SessionManager.stopContainerGraphicSession(containerName, logger)
                }
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
            }

            var vncReservedSlot: X11DisplaySlot? = null
            if (accessMode == SessionAccessMode.VNC) {
                val reservation = perf.stage("vnc.monitor-reservation") {
                    VncX11MonitorReservation.reserveBeforeVncStart(
                        containerName = containerName,
                        logger = logger
                    )
                }
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
                val monitorReady = perf.stage("vnc.ensure-x11-stopped") {
                    ensureIntegratedMonitorStoppedForVnc(containerName, vncReservedSlot, logger)
                }
                if (!monitorReady) return false
            }

            val result = when (accessMode) {
                SessionAccessMode.INTEGRATED_X11, SessionAccessMode.BOTH -> {
                    perf.stage("audio.prepare-host") {
                        prepareAudioBeforeGraphicalStart(containerName, logger)
                    }
                    val slot = perf.stage("x11.start-session") {
                        X11SessionManager.startX11Session(
                            containerName = containerName,
                            logger = logger,
                            beforeGraphicSession = {
                                perf.stage("audio.finalize-client") {
                                    finalizeAudioAfterContainerReady(containerName, logger)
                                }
                            }
                        )
                    }
                    if (slot == null) {
                        logger?.e("[-] Integrated X11 access failed")
                        false
                    } else if (!perf.stage("desktop.confirm") {
                            confirmManagedDesktop(containerName, session, slot)
                        }
                    ) {
                        logger?.e("[-] ${session.label} did not become active on ${slot.describe()}")
                        false
                    } else {
                        logger?.i("[X11] ✓ Integrated X11 ready on ${slot.describe()}")
                        logger?.i("[SESSION] ✓ ${session.label} is active through Integrated X11")
                        true
                    }
                }

                SessionAccessMode.VNC -> {
                    var vncStarted = false
                    try {
                        perf.stage("audio.prepare-host") {
                            prepareAudioBeforeGraphicalStart(containerName, logger)
                        }
                        val vncResult = perf.stage("vnc.start-standalone") {
                            VncServerManager.startStandalone(
                                containerName = containerName,
                                platform = platform,
                                session = session,
                                port = vncPort,
                                password = vncPassword,
                                logger = logger,
                                beforeGraphicSession = {
                                    perf.stage("audio.finalize-client") {
                                        finalizeAudioAfterContainerReady(containerName, logger)
                                    }
                                }
                            )
                        }
                        vncStarted = vncResult.success
                        if (vncResult.success) {
                            perf.stage("vnc.connection-guide") {
                                VncConnectionGuide.logAfterSuccessfulStart(
                                    containerName = containerName,
                                    port = vncPort,
                                    adbLocalPort = vncAdbLocalPort,
                                    displayName = vncResult.displayName,
                                    desktopUser = userPreparation.selection.userName,
                                    session = session,
                                    password = vncPassword,
                                    logger = logger
                                )
                            }
                        } else {
                            VncConnectionGuide.logAdbForwardRestartRecovery(
                                port = vncPort,
                                localPort = vncAdbLocalPort,
                                logger = logger,
                                onlyIfTroubleshooting = true
                            )
                        }
                        vncResult.success
                    } finally {
                        if (!vncStarted) {
                            perf.stage("vnc.rollback-reservation") {
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
            completed = result
            return result
        } finally {
            perf.finish(completed)
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
