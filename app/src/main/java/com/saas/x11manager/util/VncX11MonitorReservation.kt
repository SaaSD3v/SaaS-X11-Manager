package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gives a standalone-VNC container its normal Manager X11 lease without starting
 * the Integrated X11 server.
 *
 * The reservation must happen before a stopped DroidSpaces container starts so
 * /usr/.X11-unix is part of that container's mount namespace. Once VNC starts,
 * getMonitors() therefore exposes the container's Monitor N as Stopped. The user
 * may later start that monitor from Screen and obtain an independent Integrated
 * X11 desktop without restarting the VNC container.
 */
object VncX11MonitorReservation {

    suspend fun reserveBeforeVncStart(
        containerName: String,
        logger: ContainerLogger? = null
    ): Result<X11DisplaySlot?> = withContext(Dispatchers.IO) {
        try {
            logger?.i("[X11] Reserving a stopped monitor for VNC container")
            logger?.i("[CONTAINER] • Container: $containerName")
            logger?.i("[X11] • Policy: reserve only; Integrated X11 remains off")

            // This is a real lifecycle operation, so maintenance is appropriate
            // here (unlike observation from the UI).
            X11SessionManager.reconcileRuntimeState(logger)
            val containers = ContainerManager.listContainers()
            val target = containers.firstOrNull { it.name == containerName }
                ?: ContainerManager.getContainerInfo(containerName)
                ?: return@withContext Result.failure(
                    IllegalStateException("Container $containerName was not found")
                )

            val existing = ContainerConfigManager.displaySlotFromBindMounts(target.bindMounts)
            if (target.isRunning) {
                if (existing != null) {
                    logger?.i("[X11] ✓ Existing ${existing.describe()} reservation retained")
                    return@withContext Result.success(existing)
                }

                // Rewriting container.config cannot retroactively add a bind to a
                // running mount namespace. Do not silently restart user workload.
                logger?.w("[X11] ! Container is already running without a Manager X11 monitor lease")
                logger?.w("[X11] ! VNC will continue; stop/restart the container to add a toggleable X11 monitor")
                return@withContext Result.success(null)
            }

            val monitors = X11SessionManager.getMonitors(containers)
            val occupied = monitors
                .asSequence()
                .filter { monitor ->
                    monitor.containerName != null || monitor.status == X11ServerStatus.Running
                }
                .map { it.slot.number }
                .toSet()
            val slot = X11DisplayAllocator.firstFree(occupied)

            logger?.i("[X11] • Reserved monitor: ${slot.monitorNumber}")
            logger?.i("[X11] • Display: ${slot.displayName}")

            // Before recycling N, remove an old alias from any stopped container.
            for (other in containers) {
                if (other.name == containerName || other.isRunning) continue
                val oldSlot = ContainerConfigManager.displaySlotFromBindMounts(other.bindMounts)
                if (oldSlot?.number == slot.number) {
                    ContainerConfigManager.clearManualX11Config(other.name, logger)
                }
            }

            val anchorReady = Shell.cmd(
                "mkdir -p ${shellQuote(slot.socketDir)} && " +
                    "chmod 1777 ${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
                    "${shellQuote(slot.runtimeDir)} ${shellQuote(slot.socketDir)} && " +
                    "rm -f ${shellQuote(slot.socketFile)} ${shellQuote(slot.lockFile)} ${shellQuote(slot.logFile)}"
            ).exec().isSuccess
            if (!anchorReady) {
                return@withContext Result.failure(
                    IllegalStateException("Could not prepare empty ${slot.describe()} bind anchor")
                )
            }

            val configReady = ContainerConfigManager.ensureManualX11Config(
                containerName = containerName,
                logger = logger,
                displaySlot = slot
            )
            if (!configReady) {
                Shell.cmd("rm -rf ${shellQuote(slot.runtimeDir)} 2>/dev/null").exec()
                return@withContext Result.failure(
                    IllegalStateException("Could not reserve ${slot.describe()} for $containerName")
                )
            }

            logger?.i("[X11] ✓ ${slot.describe()} reserved for $containerName")
            logger?.i("[X11] ✓ Monitor is visible but stopped; start it from Screen when needed")
            Result.success(slot)
        } catch (e: Exception) {
            logger?.e("[X11] ✗ Monitor reservation failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
