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

    private fun formatPids(pids: Collection<Int>): String =
        pids.filter { it > 0 }.distinct().joinToString(",").ifEmpty { "none" }

    private suspend fun logServerContext(
        displaySlot: X11DisplaySlot,
        containerName: String?,
        logger: ContainerLogger?
    ) {
        logger?.i("[CTX] Monitor: ${displaySlot.monitorNumber}")
        logger?.i("[CTX] Display: ${displaySlot.displayName}")
        logger?.i("[CTX] Process: ${displaySlot.processName}")
        logger?.i("[CTX] Runtime: ${displaySlot.runtimeDir}")
        logger?.i("[CTX] Socket: ${displaySlot.socketFile}")
        logger?.i("[CTX] Lock: ${displaySlot.lockFile}")
        logger?.i("[CTX] Server log: ${displaySlot.logFile}")
        logger?.i("[CTX] Container owner: ${containerName ?: "none (raw monitor)"}")
    }

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
        val operationStartedAt = System.nanoTime()
        try {
            logger?.i("--- Integrated X11 Server Start ---")
            logServerContext(displaySlot, containerName, logger)
            logger?.i("[CTX] XKB root: ${Constants.INTEGRATED_X11_XKB_DIR}")
            logger?.i("[*] Inspecting existing server state...")

            val liveBefore = getLiveServerPids(displaySlot)
            val socketBefore = hasSocket(displaySlot)
            logger?.i("[CTX] Existing server PIDs: ${formatPids(liveBefore)}")
            logger?.i("[CTX] Existing socket: ${if (socketBefore) "present" else "absent"}")

            if (socketBefore && liveBefore.isNotEmpty()) {
                val pid = liveBefore.first()
                logger?.i("[+] Reusing ${displaySlot.describe()} (PID=$pid)")
                logger?.i("[CTX] Server lease: reused")
                logger?.i("[CTX] Start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
                return@withContext Result.success(
                    ServerLease(slot = displaySlot, pid = pid, reused = true)
                )
            }

            if (liveBefore.isNotEmpty()) {
                logger?.w(
                    "[!] Stale ${displaySlot.processName} process found without ${displaySlot.socketFile}; restarting it"
                )
                logger?.i("[*] Killing stale server PIDs: ${formatPids(liveBefore)}")
                killPids(liveBefore)
            }
            if (socketBefore) {
                logger?.w("[!] Stale ${displaySlot.displayName} socket found; replacing it")
            }
            logger?.i("[*] Cleaning stale socket/lock artifacts...")
            clearX11SocketFiles(displaySlot)
            logger?.i("[+] Stale runtime artifacts cleared")

            logger?.i("[*] Preparing isolated runtime directory...")
            if (!prepareRuntimeDirectory(displaySlot)) {
                logger?.e("[-] Runtime directory preparation failed: ${displaySlot.runtimeDir}")
                return@withContext Result.failure(
                    IllegalStateException(
                        "Could not prepare runtime directory for ${displaySlot.describe()}"
                    )
                )
            }
            logger?.i("[+] Runtime directory ready: ${displaySlot.runtimeDir}")

            if (hasCachedXkbConfig()) {
                logger?.i("[+] Shared XKB cache ready")
            } else {
                logger?.i("[*] Shared XKB cache is missing; staging configuration...")
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
            logger?.i("[CTX] Manager APK: $apkPath")
            logger?.i("[CTX] Entrypoint: com.termux.x11.CmdEntryPoint ${displaySlot.displayName}")
            logger?.i("[CTX] Process nice-name: ${displaySlot.processName}")
            logger?.i("[*] Launching integrated X11 app_process...")

            val launchStartedAt = System.nanoTime()
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath, displaySlot)).exec()
            val capturedPid = launch.out.asReversed()
                .firstNotNullOfOrNull { it.trim().toIntOrNull() }
            logger?.i("[CTX] Launcher exit code: ${launch.code}")
            logger?.i("[CTX] Captured launcher PID: ${capturedPid ?: "none"}")
            logger?.i("[*] Waiting up to 10s for X11 process and socket...")

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                val live = getLiveServerPids(displaySlot)
                if (hasSocket(displaySlot) && live.isNotEmpty()) {
                    val pid = when {
                        capturedPid != null && capturedPid in live -> capturedPid
                        else -> live.first()
                    }
                    val readyMs = (System.nanoTime() - launchStartedAt) / 1_000_000L
                    logger?.i("[+] ${displaySlot.describe()} ready (PID=$pid)")
                    logger?.i("[+] X11 socket: ${displaySlot.socketFile}")
                    logger?.i("[CTX] Live server PIDs: ${formatPids(live)}")
                    logger?.i("[CTX] Server readiness: ${readyMs}ms")
                    logger?.i("[CTX] Server lease: new")
                    logger?.i("[CTX] Total start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
                    return@withContext Result.success(
                        ServerLease(slot = displaySlot, pid = pid, reused = false)
                    )
                }
                delay(250)
            }

            val liveAfter = getLiveServerPids(displaySlot)
            val socketAfter = hasSocket(displaySlot)
            logger?.e("[-] X11 server readiness timed out")
            logger?.e("[-] Final live PIDs: ${formatPids(liveAfter)}")
            logger?.e("[-] Final socket state: ${if (socketAfter) "present" else "absent"}")
            logger?.e("[-] Server log: ${displaySlot.logFile}")
            if (liveAfter.isNotEmpty()) {
                logger?.i("[*] Cleaning timed-out server PIDs: ${formatPids(liveAfter)}")
            }
            killPids(liveAfter)
            clearX11SocketFiles(displaySlot)
            logger?.i("[+] Timed-out X11 runtime cleaned")
            Result.failure(
                IllegalStateException(
                    "${displaySlot.describe()} did not create ${displaySlot.socketFile}; " +
                        "see ${displaySlot.logFile}"
                )
            )
        } catch (e: Exception) {
            logger?.e("[-] Integrated X11 error on ${displaySlot.describe()}: ${e.message}")
            logger?.e("[-] Operation duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
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
        if (lease.reused) {
            logger?.i("[CTX] Rollback skipped: ${lease.slot.describe()} was reused, not created by this operation")
            return
        }
        logger?.i("--- Integrated X11 Server Rollback ---")
        logger?.i("[CTX] Monitor: ${lease.slot.monitorNumber}")
        logger?.i("[CTX] Display: ${lease.slot.displayName}")
        logger?.i("[CTX] PID: ${lease.pid}")
        logger?.i("[*] Killing newly-created X11 server after failed session start...")
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
        val operationStartedAt = System.nanoTime()
        try {
            logger?.i("--- Integrated X11 Server Stop ---")
            logServerContext(displaySlot, null, logger)
            logger?.i("[*] Inspecting server state before stop...")
            val livePids = getLiveServerPids(displaySlot)
            val socketBefore = hasSocket(displaySlot)
            logger?.i("[CTX] Live PIDs before stop: ${formatPids(livePids)}")
            logger?.i("[CTX] Socket before stop: ${if (socketBefore) "present" else "absent"}")

            if (livePids.isNotEmpty()) {
                logger?.i("[*] Sending SIGKILL to server PIDs: ${formatPids(livePids)}")
                killPids(livePids)
            } else {
                logger?.i("[+] No live ${displaySlot.processName} process found")
            }

            logger?.i("[*] Removing X11 socket and lock files...")
            clearX11SocketFiles(displaySlot)
            delay(50)

            val remainingPids = getLiveServerPids(displaySlot)
            val socketAfter = hasSocket(displaySlot)
            logger?.i("[CTX] Live PIDs after stop: ${formatPids(remainingPids)}")
            logger?.i("[CTX] Socket after stop: ${if (socketAfter) "present" else "absent"}")
            logger?.i("[CTX] Stop duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")

            if (remainingPids.isNotEmpty() || socketAfter) {
                logger?.e("[-] Could not fully stop ${displaySlot.describe()}")
                if (remainingPids.isNotEmpty()) {
                    logger?.e("[-] Remaining PIDs: ${formatPids(remainingPids)}")
                }
                if (socketAfter) {
                    logger?.e("[-] Socket still present: ${displaySlot.socketFile}")
                }
                false
            } else {
                if (livePids.isNotEmpty()) {
                    logger?.i("[+] Stopped ${displaySlot.describe()} (PIDs=${livePids.joinToString(",")})")
                } else {
                    logger?.i("[+] ${displaySlot.describe()} was already stopped")
                }
                logger?.i("[+] X11 runtime cleanup verified")
                true
            }
        } catch (e: Exception) {
            logger?.e("[-] Could not stop ${displaySlot.describe()}: ${e.message}")
            logger?.e("[-] Stop duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
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
        val operationStartedAt = System.nanoTime()

        try {
            logger?.i("--- Starting Integrated X11 Session ---")
            logger?.i("")

            val before = ContainerManager.getContainerInfo(containerName) ?: run {
                logger?.e("[-] Container $containerName was not found")
                return@withContext null
            }
            val wasRunning = before.isRunning
            logger?.i("[CTX] Container: $containerName")
            logger?.i("[CTX] Initial container status: ${before.status}")
            logger?.i("[CTX] Initial container PID: ${before.pid ?: "none"}")
            logger?.i("[CTX] Configured init: ${before.initSystem}")

            val displaySlot = selectDisplaySlotForContainer(containerName)
            logger?.i("[CTX] Selected monitor: ${displaySlot.monitorNumber}")
            logger?.i("[CTX] Selected display: ${displaySlot.displayName}")
            logger?.i("[CTX] Selected process: ${displaySlot.processName}")
            logger?.i("[CTX] Selected runtime: ${displaySlot.runtimeDir}")
            logger?.i("[CTX] Selected socket: ${displaySlot.socketFile}")

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
                logger?.i("[+] Container X11 configuration confirmed")
            }

            logger?.i("")
            val serverResult = startIntegratedServerTracked(displaySlot, containerName, logger)
            if (serverResult.isFailure) {
                logger?.e("[-] Integrated X11 failed: ${serverResult.exceptionOrNull()?.message}")
                return@withContext null
            }
            val activeServer = serverResult.getOrThrow()
            serverLease = activeServer
            logger?.i("[CTX] X11 server PID: ${activeServer.pid}")
            logger?.i("[CTX] X11 server lease: ${if (activeServer.reused) "reused" else "new"}")

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

            logger?.i("[*] Confirming container runtime (timeout 5s)...")
            val runtimeWaitStartedAt = System.nanoTime()
            val (runtimeStatus, pid) = waitForContainerRuntime(containerName)
            logger?.i("[CTX] Runtime confirmation duration: ${(System.nanoTime() - runtimeWaitStartedAt) / 1_000_000L}ms")
            when (runtimeStatus) {
                ContainerStatus.RUNNING ->
                    logger?.i("[+] Container runtime active${if (pid != null) " (PID=$pid)" else ""}")
                ContainerStatus.STOPPED ->
                    logger?.w("[!] Container runtime is currently stopped")
                ContainerStatus.UNKNOWN ->
                    logger?.w("[!] Container runtime status is still unknown")
            }

            logger?.i("[*] Waiting for container command readiness (15s)...")
            val commandWaitStartedAt = System.nanoTime()
            val commandReady = waitForContainerCommandReady(containerName)
            logger?.i("[CTX] Command readiness duration: ${(System.nanoTime() - commandWaitStartedAt) / 1_000_000L}ms")
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
            logger?.i("--- Integrated X11 Session Result ---")
            logger?.i("[CTX] Container runtime: $runtimeStatus")
            logger?.i("[CTX] Container PID: ${pid ?: "none"}")
            logger?.i("[CTX] Command channel ready: ${if (commandReady) "yes" else "no"}")
            logger?.i("[CTX] Graphic session confirmed: ${if (graphicSessionReady) "yes" else "no"}")
            logger?.i("[CTX] Monitor: ${displaySlot.monitorNumber}")
            logger?.i("[CTX] Display: ${displaySlot.displayName}")
            logger?.i("[CTX] X11 PID: ${activeServer.pid}")
            logger?.i("[CTX] Total session start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")

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
            logger?.e("[-] Session operation duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
            null
        }
    }

    suspend fun stopX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Stopping Container X11 Session ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Policy: stop container, then release its X11 monitor if unused")
        val before = ContainerManager.getContainerInfo(containerName)
        logger?.i("[CTX] Container status before stop: ${before?.status ?: ContainerStatus.UNKNOWN}")
        logger?.i("[CTX] Container PID before stop: ${before?.pid ?: "none"}")
        val displaySlot = before
            ?.takeIf { it.isRunning }
            ?.let { ContainerConfigManager.displaySlotFromBindMounts(it.bindMounts) }
        logger?.i("[CTX] Assigned display before stop: ${displaySlot?.displayName ?: "none"}")

        val stopped = ContainerManager.stopContainer(containerName, logger)
        if (!stopped) {
            logger?.e("[-] Container stop was not confirmed; X11 lease will not be released")
            return@withContext false
        }
        logger?.i("[+] Container stop confirmed")

        if (displaySlot != null) {
            val stillUsed = ContainerManager.listContainers()
                .asSequence()
                .filter { it.isRunning && it.name != containerName }
                .mapNotNull { ContainerConfigManager.displaySlotFromBindMounts(it.bindMounts) }
                .any { it.number == displaySlot.number }

            logger?.i("[CTX] Display still owned by another running container: ${if (stillUsed) "yes" else "no"}")
            if (!stillUsed) {
                val serverStopped = stopIntegratedServer(displaySlot, logger)
                if (serverStopped) {
                    logger?.i("[+] Released ${displaySlot.describe()}")
                } else {
                    logger?.w("[!] Container stopped, but ${displaySlot.describe()} cleanup was not fully confirmed")
                }
            } else {
                logger?.w("[!] ${displaySlot.describe()} is still used by another running container")
            }
        } else {
            logger?.i("[+] No Manager X11 display lease was attached to the running container")
        }

        true
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            logger?.i("--- Stopping All ---")
            val containers = ContainerManager.listContainers()
            val runningContainers = containers.filter { it.isRunning }
            logger?.i("[CTX] Running containers discovered: ${runningContainers.size}")
            logger?.i("[CTX] Running container names: ${runningContainers.joinToString(",") { it.name }.ifEmpty { "none" }}")
            for (container in runningContainers) {
                logger?.i("[*] Stopping container: ${container.name}")
                ContainerManager.stopContainer(container.name, logger)
            }

            val slots = buildSet {
                addAll(discoverRuntimeSlots().map { it.number })
                containers.forEach { container ->
                    ContainerConfigManager.displaySlotFromBindMounts(container.bindMounts)
                        ?.let { add(it.number) }
                }
            }
            logger?.i("[CTX] X11 monitor slots to clean: ${slots.sorted().joinToString(",").ifEmpty { "none" }}")
            for (number in slots.sorted()) {
                stopIntegratedServer(X11DisplaySlot(number), logger)
            }
            logger?.i("[+] All containers and X11 monitors stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
