package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class X11ServerStatus { Running, Stopped }

data class X11MonitorInfo(
    val slot: X11DisplaySlot,
    val status: X11ServerStatus,
    val pid: Int?,
    val containerName: String? = null
) {
    val monitorNumber: Int get() = slot.monitorNumber
    val displayName: String get() = slot.displayName
}

/**
 * Owns the lifecycle of Manager-integrated X11 display slots and graphical sessions.
 *
 * Every running graphical container owns one temporary monitor slot. Slots are
 * allocated dynamically from the lowest free X11 display number and are released
 * when the container session is stopped. XKB data remains shared across displays.
 */
object X11SessionManager {

    private data class ServerLease(
        val slot: X11DisplaySlot,
        val pid: Int,
        val reused: Boolean
    )

    private fun parsePids(lines: List<String>): List<Int> = lines
        .flatMap { it.trim().split(Regex("\\s+")) }
        .mapNotNull { it.toIntOrNull() }
        .filter { it > 0 }
        .distinct()

    private fun getProcessPids(processName: String): List<Int> {
        return try {
            val pidof = Shell.cmd("pidof ${shellQuote(processName)} 2>/dev/null").exec()
            val pidofPids = parsePids(pidof.out)
            if (pidofPids.isNotEmpty()) return pidofPids

            val target = shellQuote(processName)
            val proc = Shell.cmd(
                "target=$target; " +
                    "for comm in /proc/[0-9]*/comm; do " +
                    "[ -r \"\$comm\" ] || continue; " +
                    "IFS= read -r name < \"\$comm\" || continue; " +
                    "[ \"\$name\" = \"\$target\" ] || continue; " +
                    "pid=\${comm#/proc/}; pid=\${pid%/comm}; " +
                    "printf '%s\\n' \"\$pid\"; " +
                    "done"
            ).exec()
            parsePids(proc.out)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getLiveServerPids(displaySlot: X11DisplaySlot): List<Int> =
        getProcessPids(displaySlot.processName).distinct()

    private fun hasSocket(displaySlot: X11DisplaySlot): Boolean {
        return try {
            Shell.cmd("test -S ${shellQuote(displaySlot.socketFile)}").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun prepareRuntimeDirectory(displaySlot: X11DisplaySlot): Boolean {
        return try {
            Shell.cmd(
                "mkdir -p ${shellQuote(displaySlot.socketDir)} && " +
                    "chmod 1777 ${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
                    "${shellQuote(displaySlot.runtimeDir)} ${shellQuote(displaySlot.socketDir)}"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun hasCachedXkbConfig(): Boolean {
        val root = shellQuote(Constants.INTEGRATED_X11_XKB_DIR)
        return try {
            Shell.cmd(
                "test -d $root/rules && test -d $root/symbols && test -d $root/keycodes"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun stageXkbConfig(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean {
        if (hasCachedXkbConfig()) {
            logger?.i("[+] Reusing cached XKB configuration")
            return true
        }

        val info = ContainerManager.getContainerInfo(containerName) ?: run {
            logger?.e("[-] Cannot resolve container rootfs for XKB data")
            return false
        }

        val staged = RootfsAccessor.use(
            rootfsPath = info.rootfsPath,
            tag = "xkb_$containerName"
        ) { root ->
            val x11Path = "$root/usr/share/X11/xkb"
            val alternatePath = "$root/usr/share/xkeyboard-config-2"
            val source = when {
                Shell.cmd("test -d ${shellQuote(x11Path)}").exec().isSuccess -> x11Path
                Shell.cmd("test -d ${shellQuote(alternatePath)}").exec().isSuccess -> alternatePath
                else -> null
            } ?: return@use false

            val destination = Constants.INTEGRATED_X11_XKB_DIR
            val temporary = "$destination.tmp.${android.os.Process.myPid()}"
            val result = Shell.cmd(
                "rm -rf ${shellQuote(temporary)} && " +
                    "mkdir -p ${shellQuote(temporary)} && " +
                    "cp -a ${shellQuote("$source/.")} ${shellQuote("$temporary/")} && " +
                    "chmod -R a+rX ${shellQuote(temporary)} && " +
                    "rm -rf ${shellQuote(destination)} && " +
                    "mv ${shellQuote(temporary)} ${shellQuote(destination)}"
            ).exec()
            if (!result.isSuccess) {
                Shell.cmd("rm -rf ${shellQuote(temporary)} 2>/dev/null").exec()
            }
            result.isSuccess
        } ?: false

        if (staged && hasCachedXkbConfig()) {
            logger?.i("[+] Cached XKB configuration from $containerName")
            return true
        }

        logger?.e(
            "[-] XKB configuration was not found in $containerName; " +
                "expected /usr/share/X11/xkb"
        )
        return false
    }

    private fun clearX11SocketFiles(displaySlot: X11DisplaySlot) {
        Shell.cmd("rm -f ${shellQuote(displaySlot.socketFile)} 2>/dev/null").exec()
        Shell.cmd("rm -f ${shellQuote(displaySlot.lockFile)} 2>/dev/null").exec()
    }

    private fun killPids(pids: Collection<Int>) {
        val targets = pids.filter { it > 0 }.distinct()
        if (targets.isNotEmpty()) {
            Shell.cmd("kill -9 ${targets.joinToString(" ")} 2>/dev/null").exec()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun discoverRuntimeSlots(): List<X11DisplaySlot> {
        val base = shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)
        return try {
            val result = Shell.cmd(
                "for dir in $base/display-*; do " +
                    "[ -d \"\$dir\" ] || continue; " +
                    "name=\${dir##*/}; printf '%s\\n' \"\$name\"; done"
            ).exec()
            if (!result.isSuccess) return emptyList()
            result.out
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("display-")) return@mapNotNull null
                    trimmed.removePrefix("display-").toIntOrNull()
                }
                .filter { it >= 0 }
                .distinct()
                .sorted()
                .map(::X11DisplaySlot)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serverInfo(displaySlot: X11DisplaySlot, containerName: String? = null): X11MonitorInfo {
        val live = getLiveServerPids(displaySlot)
        val running = hasSocket(displaySlot) && live.isNotEmpty()
        return X11MonitorInfo(
            slot = displaySlot,
            status = if (running) X11ServerStatus.Running else X11ServerStatus.Stopped,
            pid = if (running) live.first() else null,
            containerName = containerName
        )
    }

    private fun runningAssignments(containers: List<ContainerInfo>): Map<Int, String> =
        buildMap {
            containers
                .asSequence()
                .filter { it.isRunning }
                .forEach { container ->
                    ContainerConfigManager.displaySlotFromBindMounts(container.bindMounts)
                        ?.let { slot -> putIfAbsent(slot.number, container.name) }
                }
        }

    internal fun selectDisplaySlot(
        runningAssignedDisplayNumbers: Collection<Int>
    ): X11DisplaySlot = X11DisplayAllocator.firstFree(runningAssignedDisplayNumbers)

    private suspend fun selectDisplaySlotForContainer(containerName: String): X11DisplaySlot {
        val containers = ContainerManager.listContainers()
        val target = containers.firstOrNull { it.name == containerName }
            ?: ContainerManager.getContainerInfo(containerName)

        if (target?.isRunning == true) {
            return ContainerConfigManager.displaySlotFromBindMounts(target.bindMounts)
                ?: throw IllegalStateException(
                    "Running container $containerName has no Manager display slot; stop it before starting X11"
                )
        }

        val occupied = runningAssignments(containers)
            .filterValues { it != containerName }
            .keys
        return selectDisplaySlot(occupied)
    }

    internal fun buildIntegratedServerCommand(
        apkPath: String,
        displaySlot: X11DisplaySlot = X11DisplaySlot(0)
    ): String =
        "TMPDIR=${shellQuote(displaySlot.runtimeDir)} " +
            "XKB_CONFIG_ROOT=${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} " +
            "CLASSPATH=${shellQuote(apkPath)} " +
            "/system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=${displaySlot.processName} " +
            "com.termux.x11.CmdEntryPoint ${displaySlot.displayName} " +
            ">${shellQuote(displaySlot.logFile)} 2>&1 & echo ${'$'}!"

    suspend fun getMonitors(): List<X11MonitorInfo> =
        getMonitors(ContainerManager.listContainers())

    suspend fun getMonitors(containers: List<ContainerInfo>): List<X11MonitorInfo> =
        withContext(Dispatchers.IO) {
            val assignments = runningAssignments(containers)
            val slotNumbers = buildSet {
                addAll(assignments.keys)
                addAll(discoverRuntimeSlots().map { it.number })
            }

            slotNumbers
                .sorted()
                .map { number -> serverInfo(X11DisplaySlot(number), assignments[number]) }
                .filter { it.containerName != null || it.status == X11ServerStatus.Running }
        }

    suspend fun getServerStatus(): X11ServerStatus = withContext(Dispatchers.IO) {
        if (getMonitors().any { it.status == X11ServerStatus.Running }) {
            X11ServerStatus.Running
        } else {
            X11ServerStatus.Stopped
        }
    }

    suspend fun getServerPid(): Int? = withContext(Dispatchers.IO) {
        getMonitors()
            .firstOrNull { it.status == X11ServerStatus.Running }
            ?.pid
    }

    suspend fun getServerStatus(displaySlot: X11DisplaySlot): X11ServerStatus =
        withContext(Dispatchers.IO) { serverInfo(displaySlot).status }

    suspend fun getServerPid(displaySlot: X11DisplaySlot): Int? =
        withContext(Dispatchers.IO) { serverInfo(displaySlot).pid }

    suspend fun getDisplayForContainer(containerName: String): X11DisplaySlot? =
        withContext(Dispatchers.IO) {
            val info = ContainerManager.getContainerInfo(containerName) ?: return@withContext null
            if (!info.isRunning) return@withContext null
            ContainerConfigManager.displaySlotFromBindMounts(info.bindMounts)
        }

    suspend fun ensureContainerGraphicSession(
        containerName: String,
        displaySlot: X11DisplaySlot,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.ensureRunning(
        containerName = containerName,
        displaySlot = displaySlot,
        logger = logger
    )

    suspend fun stopContainerGraphicSession(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.stop(containerName, logger)

    private suspend fun startIntegratedServerTracked(
        displaySlot: X11DisplaySlot,
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<ServerLease> = withContext(Dispatchers.IO) {
        try {
            val liveBefore = getLiveServerPids(displaySlot)
            if (hasSocket(displaySlot) && liveBefore.isNotEmpty()) {
                val pid = liveBefore.first()
                logger?.i("[+] Reusing ${displaySlot.describe()} (PID=$pid)")
                return@withContext Result.success(
                    ServerLease(slot = displaySlot, pid = pid, reused = true)
                )
            }

            if (liveBefore.isNotEmpty()) {
                logger?.w(
                    "[!] Stale ${displaySlot.processName} process found without ${displaySlot.socketFile}; restarting it"
                )
                killPids(liveBefore)
            }
            if (hasSocket(displaySlot)) {
                logger?.w("[!] Stale ${displaySlot.displayName} socket found; replacing it")
            }
            clearX11SocketFiles(displaySlot)

            if (!prepareRuntimeDirectory(displaySlot)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Could not prepare runtime directory for ${displaySlot.describe()}"
                    )
                )
            }

            if (!hasCachedXkbConfig()) {
                if (containerName.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Integrated X11 needs XKB data from a configured container before its first start"
                        )
                    )
                }
                if (!stageXkbConfig(containerName, logger)) {
                    return@withContext Result.failure(
                        IllegalStateException("Could not prepare XKB data for integrated X11")
                    )
                }
            }

            val apkPath = X11Application.instance.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not resolve SaaS X11 Manager APK path")
                )
            }

            logger?.i("[*] Starting ${displaySlot.describe()}...")
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath, displaySlot)).exec()
            val capturedPid = launch.out.asReversed()
                .firstNotNullOfOrNull { it.trim().toIntOrNull() }

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                val live = getLiveServerPids(displaySlot)
                if (hasSocket(displaySlot) && live.isNotEmpty()) {
                    val pid = when {
                        capturedPid != null && capturedPid in live -> capturedPid
                        else -> live.first()
                    }
                    logger?.i("[+] ${displaySlot.describe()} ready (PID=$pid)")
                    logger?.i("[+] X11 socket: ${displaySlot.socketFile}")
                    return@withContext Result.success(
                        ServerLease(slot = displaySlot, pid = pid, reused = false)
                    )
                }
                delay(250)
            }

            val liveAfter = getLiveServerPids(displaySlot)
            killPids(liveAfter)
            clearX11SocketFiles(displaySlot)
            Result.failure(
                IllegalStateException(
                    "${displaySlot.describe()} did not create ${displaySlot.socketFile}; " +
                        "see ${displaySlot.logFile}"
                )
            )
        } catch (e: Exception) {
            logger?.e("[-] Integrated X11 error on ${displaySlot.describe()}: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun startIntegratedServer(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = startIntegratedServerTracked(
        displaySlot = X11DisplaySlot(0),
        containerName = containerName,
        logger = logger
    ).map { it.pid }

    suspend fun startIntegratedServer(
        displaySlot: X11DisplaySlot,
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = startIntegratedServerTracked(
        displaySlot = displaySlot,
        containerName = containerName,
        logger = logger
    ).map { it.pid }

    private suspend fun rollbackServer(lease: ServerLease, logger: ContainerLogger? = null) {
        if (lease.reused) return
        killPids(listOf(lease.pid))
        clearX11SocketFiles(lease.slot)
        logger?.i("[+] Rolled back ${lease.slot.describe()} after failed session start")
    }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean =
        stopIntegratedServer(X11DisplaySlot(0), logger)

    suspend fun stopIntegratedServer(
        displaySlot: X11DisplaySlot,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val livePids = getLiveServerPids(displaySlot)
            if (livePids.isNotEmpty()) {
                killPids(livePids)
                logger?.i(
                    "[+] Stopped ${displaySlot.describe()} (PIDs=${livePids.joinToString(",")})"
                )
            }
            clearX11SocketFiles(displaySlot)
            true
        } catch (e: Exception) {
            logger?.e("[-] Could not stop ${displaySlot.describe()}: ${e.message}")
            false
        }
    }

    private suspend fun waitForContainerRuntime(
        containerName: String,
        timeoutMillis: Long = 5_000L,
        pollIntervalMillis: Long = 500L
    ): Pair<ContainerStatus, Int?> {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var latest = ContainerManager.getContainerRuntimeStatePublic(containerName)
        if (latest.first == ContainerStatus.RUNNING) return latest

        while (System.nanoTime() < deadline) {
            delay(pollIntervalMillis)
            latest = ContainerManager.getContainerRuntimeStatePublic(containerName)
            if (latest.first == ContainerStatus.RUNNING) return latest
        }

        return latest
    }

    private suspend fun waitForContainerCommandReady(
        containerName: String,
        timeoutMillis: Long = 15_000L,
        pollIntervalMillis: Long = 1_000L
    ): Boolean {
        val marker = "__SAAS_X11_READY__"
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                shellQuote("echo $marker") + " 2>/dev/null"
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L

        while (true) {
            val ready = try {
                val result = Shell.cmd(command).exec()
                result.isSuccess && result.out.any { it.contains(marker) }
            } catch (_: Exception) {
                false
            }
            if (ready) return true
            if (System.nanoTime() >= deadline) return false
            delay(pollIntervalMillis)
        }
    }

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): X11DisplaySlot? = withContext(Dispatchers.IO) {
        var serverLease: ServerLease? = null
        var containerStartAccepted = false

        try {
            logger?.i("--- Starting Integrated X11 Session ---")
            logger?.i("")

            val before = ContainerManager.getContainerInfo(containerName) ?: run {
                logger?.e("[-] Container $containerName was not found")
                return@withContext null
            }
            val wasRunning = before.isRunning
            val displaySlot = selectDisplaySlotForContainer(containerName)

            if (wasRunning) {
                logger?.i("[+] Keeping ${displaySlot.describe()} for running container $containerName")
            } else {
                logger?.i("[+] Assigned ${displaySlot.describe()} to $containerName")
                logger?.i("[*] Preparing container X11 config...")
                val configReady = ContainerConfigManager.ensureManualX11Config(
                    containerName = containerName,
                    logger = logger,
                    displaySlot = displaySlot
                )
                if (!configReady) {
                    logger?.e("[-] Container X11 config is not ready")
                    return@withContext null
                }
            }

            logger?.i("")
            val serverResult = startIntegratedServerTracked(displaySlot, containerName, logger)
            if (serverResult.isFailure) {
                logger?.e("[-] Integrated X11 failed: ${serverResult.exceptionOrNull()?.message}")
                return@withContext null
            }
            val activeServer = serverResult.getOrThrow()
            serverLease = activeServer

            logger?.i("")
            if (wasRunning) {
                containerStartAccepted = true
                logger?.i("[+] Container already running on ${displaySlot.describe()}")
            } else {
                logger?.i("[*] Starting container...")
                val started = ContainerManager.startContainer(containerName, logger)
                if (started) {
                    containerStartAccepted = true
                } else {
                    val (statusAfterFailure, _) =
                        ContainerManager.getContainerRuntimeStatePublic(containerName)
                    if (statusAfterFailure == ContainerStatus.RUNNING) {
                        containerStartAccepted = true
                        logger?.w("[!] Start command reported failure, but container is running")
                    } else if (statusAfterFailure == ContainerStatus.STOPPED) {
                        logger?.e("[-] Container start failed and runtime is stopped")
                        rollbackServer(activeServer, logger)
                        return@withContext null
                    }
                }
            }

            logger?.i("[*] Confirming container runtime...")
            val (runtimeStatus, pid) = waitForContainerRuntime(containerName)
            when (runtimeStatus) {
                ContainerStatus.RUNNING ->
                    logger?.i("[+] Container runtime active${if (pid != null) " (PID=$pid)" else ""}")
                ContainerStatus.STOPPED ->
                    logger?.w("[!] Container runtime is currently stopped")
                ContainerStatus.UNKNOWN ->
                    logger?.w("[!] Container runtime status is still unknown")
            }

            logger?.i("[*] Waiting for container command readiness (15s)...")
            val commandReady = waitForContainerCommandReady(containerName)
            if (commandReady) {
                logger?.i("[+] Container command channel ready")
            } else {
                logger?.w("[!] Container command channel is still becoming ready")
            }

            val graphicSessionReady = if (commandReady) {
                logger?.i("[*] Synchronizing configured graphic session with ${displaySlot.displayName}...")
                ensureContainerGraphicSession(containerName, displaySlot, logger)
            } else {
                false
            }

            logger?.i("")
            if (
                runtimeStatus == ContainerStatus.RUNNING &&
                commandReady &&
                graphicSessionReady
            ) {
                logger?.i("[+] Integrated X11 session started on ${displaySlot.describe()}")
            } else if (runtimeStatus == ContainerStatus.RUNNING && commandReady) {
                logger?.w(
                    "[!] ${displaySlot.describe()} is ready, but the configured graphic session is not active"
                )
            } else {
                logger?.w(
                    "[!] ${displaySlot.describe()} is ready while container startup is still settling"
                )
            }
            logger?.i("[+] Monitor: ${displaySlot.monitorNumber}")
            logger?.i("[+] X11 display: ${displaySlot.displayName}")
            displaySlot
        } catch (e: Exception) {
            if (!containerStartAccepted) {
                serverLease?.let { rollbackServer(it, logger) }
            }
            logger?.e("[-] Error: ${e.message}")
            null
        }
    }

    suspend fun stopX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val before = ContainerManager.getContainerInfo(containerName)
        val displaySlot = before
            ?.takeIf { it.isRunning }
            ?.let { ContainerConfigManager.displaySlotFromBindMounts(it.bindMounts) }

        val stopped = ContainerManager.stopContainer(containerName, logger)
        if (!stopped) return@withContext false

        if (displaySlot != null) {
            val stillUsed = ContainerManager.listContainers()
                .asSequence()
                .filter { it.isRunning && it.name != containerName }
                .mapNotNull { ContainerConfigManager.displaySlotFromBindMounts(it.bindMounts) }
                .any { it.number == displaySlot.number }

            if (!stillUsed) {
                stopIntegratedServer(displaySlot, logger)
                logger?.i("[+] Released ${displaySlot.describe()}")
            } else {
                logger?.w("[!] ${displaySlot.describe()} is still used by another running container")
            }
        }

        true
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            logger?.i("--- Stopping All ---")
            val containers = ContainerManager.listContainers()
            for (container in containers) {
                if (container.isRunning) ContainerManager.stopContainer(container.name, logger)
            }

            val slots = buildSet {
                addAll(discoverRuntimeSlots().map { it.number })
                containers.forEach { container ->
                    ContainerConfigManager.displaySlotFromBindMounts(container.bindMounts)
                        ?.let { add(it.number) }
                }
            }
            for (number in slots.sorted()) {
                stopIntegratedServer(X11DisplaySlot(number), logger)
            }
            logger?.i("[+] All containers and X11 monitors stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
