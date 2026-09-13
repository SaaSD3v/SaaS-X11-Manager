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

    // One fixed transport: serialize process/socket mutations, including recovery.
    // Desktop handshakes run outside this lock so their bounded restart can acquire it.
    private val serverMutex = Mutex()

    private data class ServerLease(val pid: Int, val reused: Boolean)
    private data class ServerRuntimeProbe(val pids: List<Int>, val socketReady: Boolean)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun parsePids(lines: List<String>): List<Int> = lines
        .flatMap { it.trim().split(Regex("\\s+")) }
        .mapNotNull(String::toIntOrNull)
        .filter { it > 0 }
        .distinct()

    /** One bounded root transaction replaces separate pid/socket probes per poll. */
    private fun probeServerRuntime(): ServerRuntimeProbe = try {
        val process = shellQuote(Constants.X11_SERVER_PROCESS)
        val socket = shellQuote(Constants.X11_SOCK_FILE)
        val result = Shell.cmd(
            "pids=${'$'}(pidof $process 2>/dev/null || true); " +
                "socket=0; [ -S $socket ] && socket=1; " +
                "printf '%s\\n' '$RUNTIME_PIDS_MARKER'\"${'$'}pids\" " +
                "'$RUNTIME_SOCKET_MARKER'\"${'$'}socket\""
        ).exec()
        val pids = result.out.firstOrNull { it.startsWith(RUNTIME_PIDS_MARKER) }
            ?.removePrefix(RUNTIME_PIDS_MARKER)
            ?.let { parsePids(listOf(it)) }
            .orEmpty()
        ServerRuntimeProbe(
            pids = pids,
            socketReady = result.out.any { it == "${RUNTIME_SOCKET_MARKER}1" }
        )
    } catch (_: Exception) {
        ServerRuntimeProbe(emptyList(), socketReady = false)
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

    private fun killPids(pids: Collection<Int>) {
        val live = pids.filter { it > 0 }.distinct()
        if (live.isNotEmpty()) {
            Shell.cmd("kill -9 ${live.joinToString(" ")} 2>/dev/null || true").exec()
        }
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

    suspend fun getOwnerContainerName(): String? = withContext(Dispatchers.IO) {
        ContainerManager.listContainers()
            .firstOrNull(FixedX11Ownership::ownsServer)
            ?.name
    }

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
                val pid = existingPids.first()
                logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} ready (PID=$pid)")
                return@withContext Result.success(ServerLease(pid, reused = true))
            }

            if (existingPids.isNotEmpty()) killPids(existingPids)
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
            val deadline = System.nanoTime() + 10_000_000_000L

            while (System.nanoTime() < deadline) {
                val runtime = probeServerRuntime()
                val pids = runtime.pids
                if (runtime.socketReady && pids.isNotEmpty()) {
                    val pid = launchedPid?.takeIf { it in pids } ?: pids.first()
                    logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} ready (PID=$pid)")
                    return@withContext Result.success(ServerLease(pid, reused = false))
                }
                delay(250)
            }

            killPids(probeServerRuntime().pids)
            clearSocketState()
            Result.failure(IllegalStateException("Integrated X11 did not create ${Constants.X11_SOCK_FILE}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    } }

    suspend fun startIntegratedServer(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = startServer(containerName, logger).map { it.pid }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean = serverMutex.withLock { withContext(Dispatchers.IO) {
        try {
            val before = probeServerRuntime()
            killPids(before.pids)
            clearSocketState()
            delay(50)
            val after = probeServerRuntime()
            val stopped = after.pids.isEmpty() && !after.socketReady
            if (stopped) {
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
        while (true) {
            val ready = try {
                val result = Shell.cmd(command).exec()
                result.isSuccess && result.out.any { it.contains(marker) }
            } catch (_: Exception) {
                false
            }
            if (ready) return true
            if (System.nanoTime() >= deadline) return false
            delay(1_000)
        }
    }

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null,
        beforeGraphicSession: (suspend () -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var lease: ServerLease? = null
        var containerStartAccepted = false

        try {
            logger?.i("--- Starting Integrated X11 Session ---")

            val containers = ContainerManager.listContainers()
            val before = containers.firstOrNull { it.name == containerName }
                ?: ContainerManager.getContainerInfo(containerName)
                ?: run {
                    logger?.e("[-] Container $containerName was not found")
                    return@withContext false
                }

            FixedX11Ownership.otherOwner(containers, containerName)?.let { owner ->
                logger?.e("[-] Integrated X11 ${Constants.X11_DISPLAY} is already in use by $owner")
                return@withContext false
            }

            if (before.isRunning) {
                if (!ContainerConfigManager.usesManagedX11(before.bindMounts)) {
                    logger?.e("[-] Stop $containerName once so its X11 bind can be updated to X0")
                    return@withContext false
                }
            } else if (!ContainerConfigManager.ensureManualX11Config(containerName, logger)) {
                logger?.e("[-] Container X11 config is not ready")
                return@withContext false
            }

            val server = startServer(containerName, logger)
            if (server.isFailure) {
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
                return@withContext false
            }

            val runtime = waitForRuntime(containerName)
            if (runtime.first != ContainerStatus.RUNNING) {
                logger?.e("[-] Container runtime was not confirmed running")
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
            if (!containerStartAccepted && lease?.reused == false) stopIntegratedServer(logger)
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
        // Legacy configurations can share X0. Stopping one container must preserve
        // another live owner, and a foreign X11 bind must never stop this server.
        if (FixedX11Ownership.canReleaseAfterStop(info, remaining)) {
            val released = stopIntegratedServer(logger)
            if (released) {
                logger?.i("[X11] ✓ Integrated X11 ${Constants.X11_DISPLAY} released")
            } else {
                logger?.e("[X11] ✗ Integrated X11 ${Constants.X11_DISPLAY} cleanup was not confirmed")
            }
            return@withContext released
        }

        val remainingOwner = remaining.firstOrNull(FixedX11Ownership::ownsServer)?.name
        logger?.i(
            "[CTX] Runtime policy: " +
                if (remainingOwner != null) {
                    "X0 retained for active owner $remainingOwner"
                } else {
                    "X0 ownership not released by this container"
                }
        )
        true
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
                } else {
                    logger?.e("[CONTAINER] ✗ Container stop was not confirmed: ${container.name}")
                }
            }
        }

        val x11Stopped = stopIntegratedServer(logger)
        if (x11Stopped) {
            logger?.i("[MANAGER] ✓ All running containers stopped; X0 released")
        } else {
            logger?.e("[MANAGER] ✗ X0 cleanup was not fully confirmed")
        }
    }
}
