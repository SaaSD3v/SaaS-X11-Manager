package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class X11ServerStatus { Running, Stopped }

data class X11ServerRuntime(
    val status: X11ServerStatus,
    val pid: Int?
)

/** Owns the Manager's fixed integrated X11 server on display :0. */
object X11SessionManager {

    private const val RUNTIME_PIDS_MARKER = "__SAAS_X11_PIDS__="
    private const val RUNTIME_SOCKET_MARKER = "__SAAS_X11_SOCKET__="
    private const val SERVER_LEASE_OWNER = "saas-x11-x0"

    // One fixed transport: serialize process/socket mutations, including recovery.
    // Desktop handshakes run outside this lock so their bounded restart can acquire it.
    private val serverMutex = Mutex()

    private data class ServerLease(
        val pid: Int,
        val startTime: String,
        val reused: Boolean
    )

    private data class ServerLeaseRecord(
        val pid: Int,
        val startTime: String
    )

    private data class ServerRuntimeProbe(val pids: List<Int>, val socketReady: Boolean)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun parsePids(lines: List<String>): List<Int> = lines
        .flatMap { it.trim().split(Regex("\\s+")) }
        .mapNotNull(String::toIntOrNull)
        .filter { it > 0 }
        .distinct()

    internal fun parseProcStartTime(statLine: String): String? {
        val close = statLine.lastIndexOf(") ")
        if (close < 0) return null
        val fields = statLine.substring(close + 2)
            .trim()
            .split(Regex("\\s+"))
        return fields.getOrNull(19)?.takeIf { value ->
            value.isNotEmpty() && value.all(Char::isDigit)
        }
    }

    private fun processStartTime(pid: Int): String? = try {
        val result = Shell.cmd("cat /proc/$pid/stat 2>/dev/null").exec()
        if (!result.isSuccess) null
        else parseProcStartTime(result.out.joinToString(" "))
    } catch (_: Exception) {
        null
    }

    private fun hasKernelSocket(lines: List<String>): Boolean =
        UnixSocketTableParser.findInode(lines, Constants.X11_SOCK_FILE) != null ||
            UnixSocketTableParser.findInode(lines, "@${Constants.X11_SOCK_FILE}") != null

    private fun parseServerRuntime(lines: List<String>): ServerRuntimeProbe {
        val pids = lines.firstOrNull { it.startsWith(RUNTIME_PIDS_MARKER) }
            ?.removePrefix(RUNTIME_PIDS_MARKER)
            ?.let { parsePids(listOf(it)) }
            .orEmpty()
        return ServerRuntimeProbe(
            pids = pids,
            socketReady = lines.any { it == "${RUNTIME_SOCKET_MARKER}1" } &&
                hasKernelSocket(lines)
        )
    }

    /** One root transaction resolves process, filesystem socket and kernel socket. */
    private fun probeServerRuntime(): ServerRuntimeProbe = try {
        val process = shellQuote(Constants.X11_SERVER_PROCESS)
        val socket = shellQuote(Constants.X11_SOCK_FILE)
        val result = Shell.cmd(
            "pids=${'$'}(pidof $process 2>/dev/null || true); " +
                "socket=0; [ -S $socket ] && socket=1; " +
                "printf '%s\\n' '$RUNTIME_PIDS_MARKER'\"${'$'}pids\" " +
                "'$RUNTIME_SOCKET_MARKER'\"${'$'}socket\"; " +
                "cat /proc/net/unix 2>/dev/null"
        ).exec()
        parseServerRuntime(result.out)
    } catch (_: Exception) {
        ServerRuntimeProbe(emptyList(), socketReady = false)
    }

    /**
     * Startup readiness stays inside one libsu/root transaction. This avoids up
     * to forty APK -> root round trips on old Android kernels while still
     * requiring the X0 pathname to be a live kernel UNIX socket.
     */
    private fun waitForServerRuntime(): ServerRuntimeProbe = try {
        val process = shellQuote(Constants.X11_SERVER_PROCESS)
        val socket = shellQuote(Constants.X11_SOCK_FILE)
        val kernelNeedle = shellQuote(" ${Constants.X11_SOCK_FILE}")
        val result = Shell.cmd(
            "attempt=0; pids=''; socket=0; " +
                "while [ \"${'$'}attempt\" -lt 40 ]; do " +
                "pids=${'$'}(pidof $process 2>/dev/null || true); socket=0; " +
                "if [ -n \"${'$'}pids\" ] && [ -S $socket ] && " +
                "grep -Fq $kernelNeedle /proc/net/unix 2>/dev/null; then socket=1; break; fi; " +
                "attempt=${'$'}((attempt + 1)); sleep 0.25; " +
                "done; " +
                "printf '%s\\n' '$RUNTIME_PIDS_MARKER'\"${'$'}pids\" " +
                "'$RUNTIME_SOCKET_MARKER'\"${'$'}socket\"; " +
                "cat /proc/net/unix 2>/dev/null"
        ).exec()
        parseServerRuntime(result.out)
    } catch (_: Exception) {
        ServerRuntimeProbe(emptyList(), socketReady = false)
    }

    private fun readServerLease(): ServerLeaseRecord? = try {
        val result = Shell.cmd("cat ${shellQuote(Constants.X11_LEASE_FILE)} 2>/dev/null").exec()
        if (!result.isSuccess) return null
        val values = result.out.mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null
            else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        if (values["owner"] != SERVER_LEASE_OWNER) return null
        val pid = values["pid"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val start = values["start"]?.takeIf { it.isNotBlank() } ?: return null
        ServerLeaseRecord(pid, start)
    } catch (_: Exception) {
        null
    }

    private fun writeServerLease(record: ServerLeaseRecord): Boolean = try {
        val temp = "${Constants.X11_LEASE_FILE}.tmp.${android.os.Process.myPid()}"
        Shell.cmd(
            "umask 077; " +
                "printf '%s\\n' " +
                "${shellQuote("owner=$SERVER_LEASE_OWNER")} " +
                "${shellQuote("pid=${record.pid}")} " +
                "${shellQuote("start=${record.startTime}")} " +
                "> ${shellQuote(temp)} && chmod 600 ${shellQuote(temp)} && " +
                "mv -f ${shellQuote(temp)} ${shellQuote(Constants.X11_LEASE_FILE)}"
        ).exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun removeServerLease() {
        try {
            Shell.cmd("rm -f ${shellQuote(Constants.X11_LEASE_FILE)} 2>/dev/null || true").exec()
        } catch (_: Exception) {
        }
    }

    private fun ensureServerLease(pid: Int): ServerLeaseRecord? {
        val start = processStartTime(pid) ?: return null
        val existing = readServerLease()
        if (existing?.pid == pid && existing.startTime == start) return existing
        val record = ServerLeaseRecord(pid, start)
        return record.takeIf(::writeServerLease)
    }

    private fun leaseMatches(record: ServerLeaseRecord, pids: Collection<Int>): Boolean =
        record.pid in pids && processStartTime(record.pid) == record.startTime

    /**
     * Stop only a process whose PID generation is proven. A single exact
     * saas-x11 process from an older build may be adopted once; ambiguous
     * multiple generations fail closed instead of being mass-killed.
     */
    private suspend fun stopOwnedServer(
        pids: Collection<Int>,
        logger: ContainerLogger? = null
    ): Boolean {
        val live = pids.filter { it > 0 }.distinct()
        if (live.isEmpty()) {
            removeServerLease()
            return true
        }

        val recorded = readServerLease()?.takeIf { leaseMatches(it, live) }
        val owned = recorded ?: if (live.size == 1) ensureServerLease(live.single()) else null
        if (owned == null) {
            logger?.e("[-] Refusing to kill ambiguous ${Constants.X11_SERVER_PROCESS} processes: ${live.joinToString(",")}")
            return false
        }

        Shell.cmd("kill ${owned.pid} 2>/dev/null || true").exec()
        delay(150)
        if (processStartTime(owned.pid) == owned.startTime) {
            Shell.cmd("kill -9 ${owned.pid} 2>/dev/null || true").exec()
            delay(50)
        }

        val stopped = processStartTime(owned.pid) != owned.startTime
        if (stopped) removeServerLease()
        return stopped
    }

    private fun prepareRuntime(): Boolean = try {
        Shell.cmd(
            "mkdir -p ${shellQuote(Constants.X11_SOCK_DIR)} && " +
                "chmod 1777 ${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
                "${shellQuote(Constants.X11_SOCK_DIR)}"
        ).exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun clearSocketState() {
        Shell.cmd(
            "rm -f ${shellQuote(Constants.X11_SOCK_FILE)} " +
                "${shellQuote(Constants.X11_LOCK_FILE)} 2>/dev/null || true"
        ).exec()
    }

    private fun xkbReady(): Boolean {
        val root = shellQuote(Constants.INTEGRATED_X11_XKB_DIR)
        return try {
            Shell.cmd("test -d $root/rules && test -d $root/symbols && test -d $root/keycodes").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun stageXkb(containerName: String, logger: ContainerLogger?): Boolean {
        if (xkbReady()) return true

        val info = ContainerManager.getContainerInfo(containerName) ?: return false
        val copied = RootfsAccessor.use(info.rootfsPath, "xkb_$containerName") { root ->
            val primary = "$root/usr/share/X11/xkb"
            val alternate = "$root/usr/share/xkeyboard-config-2"
            val source = when {
                Shell.cmd("test -d ${shellQuote(primary)}").exec().isSuccess -> primary
                Shell.cmd("test -d ${shellQuote(alternate)}").exec().isSuccess -> alternate
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
            if (!result.isSuccess) Shell.cmd("rm -rf ${shellQuote(temporary)} 2>/dev/null").exec()
            result.isSuccess
        } ?: false

        if (copied && xkbReady()) {
            logger?.i("[+] XKB configuration ready")
            return true
        }
        logger?.e("[-] XKB configuration was not found in $containerName")
        return false
    }

    internal fun buildIntegratedServerCommand(apkPath: String): String =
        "TMPDIR=${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
            "XKB_CONFIG_ROOT=${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} " +
            "CLASSPATH=${shellQuote(apkPath)} " +
            "/system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=${Constants.X11_SERVER_PROCESS} " +
            "com.termux.x11.CmdEntryPoint ${Constants.X11_DISPLAY} " +
            ">${shellQuote(Constants.X11_LOG_FILE)} 2>&1 & echo ${'$'}!"

    suspend fun getServerRuntime(): X11ServerRuntime = withContext(Dispatchers.IO) {
        val runtime = probeServerRuntime()
        val running = runtime.socketReady && runtime.pids.isNotEmpty()
        X11ServerRuntime(
            status = if (running) X11ServerStatus.Running else X11ServerStatus.Stopped,
            pid = if (running) runtime.pids.first() else null
        )
    }

    suspend fun getServerStatus(): X11ServerStatus = getServerRuntime().status

    suspend fun getServerPid(): Int? = getServerRuntime().pid

    suspend fun getOwnerContainerNames(): List<String> = withContext(Dispatchers.IO) {
        FixedX11Ownership.owners(ContainerManager.listContainers())
    }

    suspend fun getOwnerContainerName(): String? =
        getOwnerContainerNames().singleOrNull()

    suspend fun ensureContainerGraphicSession(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.ensureRunning(containerName, logger)

    suspend fun stopContainerGraphicSession(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.stop(containerName, logger)

    private suspend fun startServer(
        containerName: String?,
        logger: ContainerLogger?
    ): Result<ServerLease> = serverMutex.withLock { withContext(Dispatchers.IO) {
        try {
            val existing = probeServerRuntime()
            val existingPids = existing.pids
            if (existing.socketReady && existingPids.isNotEmpty()) {
                if (existingPids.size != 1) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Multiple ${Constants.X11_SERVER_PROCESS} processes own fixed X0: " +
                                existingPids.joinToString(",")
                        )
                    )
                }
                val pid = existingPids.single()
                val record = ensureServerLease(pid)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Could not establish the fixed X0 process lease")
                    )
                logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} ready (PID=$pid)")
                return@withContext Result.success(
                    ServerLease(pid, record.startTime, reused = true)
                )
            }

            if (existingPids.isNotEmpty()) {
                logger?.w("[!] Stale fixed X0 process found without a live kernel socket")
                if (!stopOwnedServer(existingPids, logger)) {
                    return@withContext Result.failure(
                        IllegalStateException("Ambiguous stale X0 process ownership")
                    )
                }
            }
            removeServerLease()
            clearSocketState()
            if (!prepareRuntime()) {
                return@withContext Result.failure(IllegalStateException("Could not prepare X11 runtime"))
            }

            if (!xkbReady()) {
                if (containerName.isNullOrBlank() || !stageXkb(containerName, logger)) {
                    return@withContext Result.failure(
                        IllegalStateException("XKB data is required before the first X11 start")
                    )
                }
            }

            val apkPath = X11Application.instance.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Manager APK path is unavailable"))
            }

            logger?.i("[*] Starting Integrated X11 ${Constants.X11_DISPLAY}")
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath)).exec()
            val launchedPid = launch.out.asReversed().firstNotNullOfOrNull { it.trim().toIntOrNull() }

            val runtime = waitForServerRuntime()
            val pids = runtime.pids
            if (runtime.socketReady && pids.size == 1) {
                val pid = launchedPid?.takeIf { it in pids } ?: pids.single()
                val record = ensureServerLease(pid)
                if (record != null) {
                    logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} ready (PID=$pid)")
                    return@withContext Result.success(
                        ServerLease(pid, record.startTime, reused = false)
                    )
                }
            }

            if (launchedPid != null) {
                val launchedStart = processStartTime(launchedPid)
                if (launchedStart != null) {
                    val temporaryLease = ServerLeaseRecord(launchedPid, launchedStart)
                    if (writeServerLease(temporaryLease)) {
                        stopOwnedServer(listOf(launchedPid), logger)
                    }
                }
            }
            clearSocketState()
            removeServerLease()
            val reason = if (pids.size > 1) {
                "Integrated X11 produced multiple fixed-X0 processes: ${pids.joinToString(",")}"
            } else {
                "Integrated X11 did not create a live ${Constants.X11_SOCK_FILE}"
            }
            Result.failure(IllegalStateException(reason))
        } catch (e: Exception) {
            Result.failure(e)
        }
    } }

    suspend fun startIntegratedServer(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = startServer(containerName, logger).map { it.pid }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean =
        serverMutex.withLock { withContext(Dispatchers.IO) {
            try {
                val before = probeServerRuntime()
                if (!stopOwnedServer(before.pids, logger)) {
                    logger?.e("[-] Integrated X11 process ownership could not be proven")
                    return@withContext false
                }
                clearSocketState()
                delay(50)
                val after = probeServerRuntime()
                val stopped = after.pids.isEmpty() && !after.socketReady
                if (stopped) {
                    removeServerLease()
                    logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} stopped")
                } else {
                    logger?.e("[-] Integrated X11 ${Constants.X11_DISPLAY} did not stop cleanly")
                }
                stopped
            } catch (e: Exception) {
                logger?.e("[-] Could not stop Integrated X11: ${e.message}")
                false
            }
        } }

    private suspend fun waitForRuntime(containerName: String): Pair<ContainerStatus, Int?> {
        val deadline = System.nanoTime() + 5_000_000_000L
        var state = ContainerManager.getContainerRuntimeStatePublic(containerName)
        while (state.first != ContainerStatus.RUNNING && System.nanoTime() < deadline) {
            delay(500)
            state = ContainerManager.getContainerRuntimeStatePublic(containerName)
        }
        return state
    }

    private suspend fun waitForCommand(containerName: String): Boolean {
        val marker = "__SAAS_X11_READY__"
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                shellQuote("echo $marker") + " 2>/dev/null"
        val deadline = System.nanoTime() + 15_000_000_000L
        var backoffMs = 250L

        while (true) {
            val ready = try {
                val result = Shell.cmd(command).exec()
                result.isSuccess && result.out.any { it.contains(marker) }
            } catch (_: Exception) {
                false
            }
            if (ready) return true

            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) return false
            delay(minOf(backoffMs, remainingMs))
            backoffMs = (backoffMs * 2).coerceAtMost(3_000L)
        }
    }

    private suspend fun reconcileStoppedContainerLeases(
        containers: List<ContainerInfo>,
        logger: ContainerLogger?
    ): Boolean {
        var changed = false
        for (container in containers) {
            if (container.isRunning || !ContainerConfigManager.usesManagedX11(container.bindMounts)) {
                continue
            }
            if (ContainerConfigManager.clearManualX11Config(container.name, logger)) {
                changed = true
            }
        }
        return changed
    }

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null,
        beforeGraphicSession: (suspend () -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var lease: ServerLease? = null
        var containerStartAccepted = false
        var configPreparedForStoppedContainer = false

        try {
            logger?.i("--- Starting Integrated X11 Session ---")

            var containers = ContainerManager.listContainers()
            if (reconcileStoppedContainerLeases(containers, logger)) {
                containers = ContainerManager.listContainers()
            }

            val before = containers.firstOrNull { it.name == containerName }
                ?: ContainerManager.getContainerInfo(containerName)
                ?: run {
                    logger?.e("[-] Container $containerName was not found")
                    return@withContext false
                }

            val otherOwners = FixedX11Ownership.otherOwners(containers, containerName)
            if (otherOwners.isNotEmpty()) {
                logger?.e(
                    "[-] Integrated X11 ${Constants.X11_DISPLAY} is already owned by " +
                        otherOwners.joinToString(", ")
                )
                return@withContext false
            }

            if (before.isRunning) {
                if (!ContainerConfigManager.usesManagedX11(before.bindMounts)) {
                    logger?.e("[-] Stop $containerName once so its X11 bind can be updated to X0")
                    return@withContext false
                }
            } else {
                if (!ContainerConfigManager.ensureManualX11Config(containerName, logger)) {
                    logger?.e("[-] Container X11 config is not ready")
                    return@withContext false
                }
                configPreparedForStoppedContainer = true
            }

            val server = startServer(containerName, logger)
            if (server.isFailure) {
                if (configPreparedForStoppedContainer) {
                    ContainerConfigManager.clearManualX11Config(containerName, logger)
                }
                logger?.e("[-] Integrated X11 failed: ${server.exceptionOrNull()?.message}")
                return@withContext false
            }
            lease = server.getOrThrow()

            if (before.isRunning) {
                containerStartAccepted = true
            } else {
                containerStartAccepted = ContainerManager.startContainer(containerName, logger)
                if (!containerStartAccepted) {
                    val state = ContainerManager.getContainerRuntimeStatePublic(containerName)
                    containerStartAccepted = state.first == ContainerStatus.RUNNING
                }
            }

            if (!containerStartAccepted) {
                if (lease.reused.not()) stopIntegratedServer(logger)
                if (configPreparedForStoppedContainer) {
                    ContainerConfigManager.clearManualX11Config(containerName, logger)
                }
                return@withContext false
            }

            val runtime = waitForRuntime(containerName)
            if (runtime.first != ContainerStatus.RUNNING) {
                logger?.e("[-] Container runtime was not confirmed running")
                if (runtime.first == ContainerStatus.STOPPED) {
                    if (lease.reused.not()) stopIntegratedServer(logger)
                    ContainerConfigManager.clearManualX11Config(containerName, logger)
                }
                return@withContext false
            }
            logger?.i("[+] Container runtime active${runtime.second?.let { " (PID=$it)" }.orEmpty()}")

            if (!waitForCommand(containerName)) {
                logger?.e("[-] Container command channel did not become ready")
                return@withContext false
            }
            logger?.i("[+] Container command channel ready")

            beforeGraphicSession?.invoke()
            if (!ensureContainerGraphicSession(containerName, logger)) {
                logger?.e("[-] Configured graphic session did not become active on ${Constants.X11_DISPLAY}")
                return@withContext false
            }

            logger?.i("[+] Integrated X11 session started on ${Constants.X11_DISPLAY}")
            true
        } catch (e: Exception) {
            if (!containerStartAccepted) {
                if (lease?.reused == false) stopIntegratedServer(logger)
                if (configPreparedForStoppedContainer) {
                    ContainerConfigManager.clearManualX11Config(containerName, logger)
                }
            }
            logger?.e("[-] Integrated X11 session error: ${e.message}")
            false
        }
    }

    suspend fun stopX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Stopping Container X11 Session ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Assigned display before stop: ${Constants.X11_DISPLAY}")

        val info = ContainerManager.getContainerInfo(containerName)
        if (!ContainerManager.stopContainer(containerName, logger)) {
            logger?.e("[-] Container stop was not confirmed; X0 ownership will not be released")
            return@withContext false
        }
        logger?.i("[+] Container stop confirmed")

        val remaining = ContainerManager.listContainers()
        val shouldReleaseServer = FixedX11Ownership.canReleaseAfterStop(info, remaining)
        val serverReleased = if (shouldReleaseServer) {
            val released = stopIntegratedServer(logger)
            if (released) {
                logger?.i("[X11] ✓ Integrated X11 ${Constants.X11_DISPLAY} released")
            } else {
                logger?.e("[X11] ✗ Integrated X11 ${Constants.X11_DISPLAY} cleanup was not confirmed")
            }
            released
        } else {
            val remainingOwners = FixedX11Ownership.owners(remaining)
            logger?.i(
                "[CTX] Runtime policy: " +
                    if (remainingOwners.isNotEmpty()) {
                        "X0 retained for active owner(s): ${remainingOwners.joinToString(", ")}"
                    } else {
                        "X0 ownership was not held by this container"
                    }
            )
            true
        }

        val configReleased = ContainerConfigManager.clearManualX11Config(containerName, logger)
        if (!configReleased) {
            logger?.w("[!] Container stopped, but its persistent fixed-X0 bind could not be cleared")
        }

        serverReleased && configReleased
    }

    suspend fun stopAll(
        logger: ContainerLogger? = null,
        containersSnapshot: List<ContainerInfo>? = null
    ) = withContext(Dispatchers.IO) {
        logger?.i("--- Stopping All ---")
        val containers = containersSnapshot
            ?.takeIf { it.isNotEmpty() }
            ?: ContainerManager.listContainers()
        val running = containers.filter { it.isRunning }
        if (running.isEmpty()) {
            logger?.i("[CONTAINER] ✓ No running containers to stop")
        } else {
            running.forEach { container ->
                logger?.i("[CONTAINER] Stopping container: ${container.name}")
                val stopped = ContainerManager.stopContainer(container.name, logger)
                if (stopped) {
                    logger?.i("[CONTAINER] ✓ Container stopped: ${container.name}")
                    if (!ContainerConfigManager.clearManualX11Config(container.name, logger)) {
                        logger?.w("[CONTAINER] ! Could not clear fixed-X0 bind for ${container.name}")
                    }
                } else {
                    logger?.e("[CONTAINER] ✗ Container stop was not confirmed: ${container.name}")
                }
            }
        }

        val after = ContainerManager.listContainers()
        reconcileStoppedContainerLeases(after, logger)
        val survivingOwners = FixedX11Ownership.owners(ContainerManager.listContainers())
        if (survivingOwners.isNotEmpty()) {
            logger?.w(
                "[MANAGER] ! X0 retained because running owner(s) remain: " +
                    survivingOwners.joinToString(", ")
            )
            return@withContext
        }

        val x11Stopped = stopIntegratedServer(logger)
        if (x11Stopped) {
            logger?.i("[MANAGER] ✓ All running containers stopped; X0 released")
        } else {
            logger?.e("[MANAGER] ✗ X0 cleanup was not fully confirmed")
        }
    }
}
