package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LoaderStatus { Running, Stopped }

enum class X11SessionStartState {
    STARTED,
    OWNER_CONFLICT,
    CONFIG_FAILED,
    SERVER_FAILED,
    CONTAINER_FAILED,
    CONTAINER_NOT_READY,
    GRAPHIC_SESSION_FAILED
}

data class X11SessionStartResult(
    val state: X11SessionStartState,
    val containerStatus: ContainerStatus = ContainerStatus.UNKNOWN,
    val containerPid: Int? = null,
    val serverPid: Int? = null,
    val message: String
) {
    val success: Boolean get() = state == X11SessionStartState.STARTED
}

data class X11ServerSnapshot(
    val status: LoaderStatus,
    val pid: Int?,
    val socketPresent: Boolean
)

/** Owns the project X11 server process, socket, XKB bootstrap and container session startup. */
object X11SessionManager {

    private data class ServerLease(
        val pid: Int,
        val reused: Boolean
    )

    private data class ServerProbe(
        val socketPresent: Boolean,
        val pids: List<Int>
    ) {
        val healthy: Boolean get() = socketPresent && pids.isNotEmpty()
        val firstPid: Int? get() = pids.firstOrNull()
    }

    /**
     * UI-level busy flags are presentation only. All mutations of the single :0
     * runtime are serialized here so Home, Screen and Edit cannot race each other.
     */
    private val lifecycleMutex = Mutex()

    /**
     * The branch is intentionally single-display. Persist the current container
     * lease next to the X11 runtime so an app-process restart does not forget who
     * owns :0. Stale leases are validated against DroidSpaces before use.
     */
    private val ownerFile = "${Constants.INTEGRATED_X11_RUNTIME_DIR}/owner"

    private fun readServerProbe(): ServerProbe {
        val socket = shellQuote(Constants.X11_SOCK_FILE)
        val processName = shellQuote(Constants.X11_SERVER_PROCESS)
        val command =
            "socket=0; [ -S $socket ] && socket=1; " +
                "printf 'SOCKET=%s\\n' \"${'$'}socket\"; " +
                "for comm in /proc/[0-9]*/comm; do " +
                "[ -r \"${'$'}comm\" ] || continue; " +
                "name=${'$'}(cat \"${'$'}comm\" 2>/dev/null) || continue; " +
                "[ \"${'$'}name\" = $processName ] || continue; " +
                "pid=${'$'}{comm#/proc/}; pid=${'$'}{pid%/comm}; " +
                "printf 'PID=%s\\n' \"${'$'}pid\"; " +
                "done"

        return try {
            val result = Shell.cmd(command).exec()
            var socketPresent = false
            val pids = mutableListOf<Int>()
            result.out.forEach { raw ->
                val line = raw.trim()
                when {
                    line == "SOCKET=1" -> socketPresent = true
                    line.startsWith("PID=") -> line.removePrefix("PID=")
                        .toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.let(pids::add)
                }
            }
            ServerProbe(socketPresent = socketPresent, pids = pids.distinct())
        } catch (_: Exception) {
            ServerProbe(socketPresent = false, pids = emptyList())
        }
    }

    private fun prepareRuntimeDirectory(): Boolean {
        return try {
            Shell.cmd(
                "mkdir -p ${shellQuote(Constants.X11_SOCK_DIR)} && " +
                    "chmod 1777 ${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
                    "${shellQuote(Constants.X11_SOCK_DIR)}"
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
            "[-] XKB configuration was not found in $containerName; expected /usr/share/X11/xkb"
        )
        return false
    }

    private fun clearX11SocketFiles() {
        Shell.cmd(
            "rm -f ${shellQuote(Constants.X11_SOCK_FILE)} " +
                "${shellQuote(Constants.X11_LOCK_FILE)} 2>/dev/null"
        ).exec()
    }

    private fun readOwner(): String? {
        return try {
            val result = Shell.cmd("cat ${shellQuote(ownerFile)} 2>/dev/null").exec()
            if (!result.isSuccess) null else result.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeOwner(containerName: String): Boolean {
        if (!prepareRuntimeDirectory()) return false
        val temporary = "$ownerFile.tmp.${android.os.Process.myPid()}"
        return try {
            Shell.cmd(
                "printf '%s\\n' ${shellQuote(containerName)} > ${shellQuote(temporary)} && " +
                    "chmod 600 ${shellQuote(temporary)} && " +
                    "mv ${shellQuote(temporary)} ${shellQuote(ownerFile)}"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun clearOwner() {
        try {
            Shell.cmd("rm -f ${shellQuote(ownerFile)} 2>/dev/null").exec()
        } catch (_: Exception) {
        }
    }

    private fun hasManagerSocketBind(bindMounts: String): Boolean {
        return bindMounts.split(',')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .any { entry ->
                val parts = entry.split(':')
                parts.size >= 2 &&
                    parts[0].trim() == Constants.X11_SOCK_DIR &&
                    parts[1].trim() == "/usr/.X11-unix"
            }
    }

    private fun isManagedGraphicSessionActive(containerName: String): Boolean {
        val probe =
            "if command -v systemctl >/dev/null 2>&1 && " +
                "test -f /etc/systemd/system/x11-session.service; then " +
                "systemctl is-active --quiet x11-session.service; " +
                "elif command -v rc-service >/dev/null 2>&1 && " +
                "test -x /etc/init.d/x11-session; then " +
                "rc-service x11-session status >/dev/null 2>&1; " +
                "else exit 1; fi"
        val command =
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                "sh -c ${shellQuote(probe)} 2>/dev/null"
        return try {
            Shell.cmd(command).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun activeOwnerConflict(containerName: String): String? {
        val persistedOwner = readOwner()
        if (!persistedOwner.isNullOrBlank() && persistedOwner != containerName) {
            val (ownerStatus, _) = ContainerManager.getContainerRuntimeStatePublic(persistedOwner)
            when (ownerStatus) {
                ContainerStatus.RUNNING -> return persistedOwner
                ContainerStatus.STOPPED -> clearOwner()
                ContainerStatus.UNKNOWN -> return persistedOwner
            }
        }

        // Compatibility fallback for sessions started before the owner marker
        // existed. A mere bind is not ownership: require the Manager-provisioned
        // x11-session service to be active inside the running container as well.
        val discovered = ContainerManager.listContainers().firstOrNull { container ->
            container.name != containerName &&
                container.isRunning &&
                hasManagerSocketBind(container.bindMounts) &&
                isManagedGraphicSessionActive(container.name)
        }
        if (discovered != null) {
            writeOwner(discovered.name)
            return discovered.name
        }

        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    internal fun buildIntegratedServerCommand(apkPath: String): String =
        "TMPDIR=${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
            "XKB_CONFIG_ROOT=${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} " +
            "CLASSPATH=${shellQuote(apkPath)} " +
            "/system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=${Constants.X11_SERVER_PROCESS} " +
            "com.termux.x11.CmdEntryPoint ${Constants.X11_DISPLAY} " +
            ">${shellQuote(Constants.X11_LOG_FILE)} 2>&1 & echo ${'$'}!"

    suspend fun getServerSnapshot(): X11ServerSnapshot = withContext(Dispatchers.IO) {
        val probe = readServerProbe()
        X11ServerSnapshot(
            status = if (probe.healthy) LoaderStatus.Running else LoaderStatus.Stopped,
            pid = probe.firstPid,
            socketPresent = probe.socketPresent
        )
    }

    suspend fun getLoaderStatus(): LoaderStatus = getServerSnapshot().status

    suspend fun getLoaderPid(): Int? = getServerSnapshot().pid

    private suspend fun terminateServerPids(pids: Collection<Int>) {
        val targets = pids.filter { it > 0 }.distinct()
        if (targets.isEmpty()) return

        Shell.cmd("kill ${targets.joinToString(" ")} 2>/dev/null").exec()
        repeat(5) {
            delay(100)
            val remaining = readServerProbe().pids.filter { it in targets }
            if (remaining.isEmpty()) return
        }

        Shell.cmd("kill -9 ${targets.joinToString(" ")} 2>/dev/null").exec()
        delay(100)
    }

    private suspend fun startIntegratedServerTrackedLocked(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<ServerLease> {
        return try {
            val before = readServerProbe()
            if (before.healthy) {
                val pid = requireNotNull(before.firstPid)
                logger?.i("[+] Reusing integrated X11 server ${Constants.X11_DISPLAY} (PID=$pid)")
                return Result.success(ServerLease(pid = pid, reused = true))
            }

            if (before.pids.isNotEmpty()) {
                logger?.w("[!] Stale SaaS X11 process found without X0 socket; restarting it")
                terminateServerPids(before.pids)
            }
            if (before.socketPresent) {
                logger?.w("[!] Stale integrated X0 socket found; replacing it")
            }
            clearX11SocketFiles()

            if (!prepareRuntimeDirectory()) {
                return Result.failure(
                    IllegalStateException("Could not prepare integrated X11 runtime directory")
                )
            }

            if (!hasCachedXkbConfig()) {
                if (containerName.isNullOrBlank()) {
                    return Result.failure(
                        IllegalStateException(
                            "Integrated X11 needs XKB data from a configured container before its first start"
                        )
                    )
                }
                if (!stageXkbConfig(containerName, logger)) {
                    return Result.failure(
                        IllegalStateException("Could not prepare XKB data for integrated X11")
                    )
                }
            }

            val apkPath = X11Application.instance.applicationInfo.sourceDir
            if (apkPath.isNullOrBlank()) {
                return Result.failure(
                    IllegalStateException("Could not resolve SaaS X11 Manager APK path")
                )
            }

            logger?.i("[*] Starting integrated X11 server ${Constants.X11_DISPLAY}...")
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath)).exec()
            val capturedPid = launch.out.asReversed()
                .firstNotNullOfOrNull { it.trim().toIntOrNull() }

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                val live = readServerProbe()
                if (live.healthy) {
                    val pid = when {
                        capturedPid != null && capturedPid in live.pids -> capturedPid
                        else -> requireNotNull(live.firstPid)
                    }
                    logger?.i("[+] Integrated X11 server ready (PID=$pid)")
                    logger?.i("[+] Display: ${Constants.X11_DISPLAY}")
                    logger?.i("[+] X11 socket: ${Constants.X11_SOCK_FILE}")
                    return Result.success(ServerLease(pid = pid, reused = false))
                }
                delay(250)
            }

            val after = readServerProbe()
            terminateServerPids(after.pids)
            clearX11SocketFiles()
            Result.failure(
                IllegalStateException(
                    "Integrated X11 server did not create ${Constants.X11_SOCK_FILE}; " +
                        "see ${Constants.X11_LOG_FILE}"
                )
            )
        } catch (e: Exception) {
            logger?.e("[-] Integrated X11 server error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun startIntegratedServer(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            startIntegratedServerTrackedLocked(containerName, logger).map { it.pid }
        }
    }

    private suspend fun rollbackServer(lease: ServerLease, logger: ContainerLogger? = null) {
        if (lease.reused) return
        terminateServerPids(listOf(lease.pid))
        clearX11SocketFiles()
        preserveOrClearOwnerAfterServerStop(logger)
        logger?.i("[+] Rolled back integrated X11 server after failed session start")
    }

    private suspend fun preserveOrClearOwnerAfterServerStop(logger: ContainerLogger? = null) {
        val owner = readOwner() ?: return
        val (ownerStatus, _) = ContainerManager.getContainerRuntimeStatePublic(owner)
        when (ownerStatus) {
            ContainerStatus.RUNNING -> logger?.i(
                "[+] Preserving display owner $owner while its container remains running"
            )
            ContainerStatus.STOPPED -> clearOwner()
            ContainerStatus.UNKNOWN -> logger?.w(
                "[!] Display owner $owner could not be verified; preserving the lease conservatively"
            )
        }
    }

    private suspend fun stopIntegratedServerLocked(logger: ContainerLogger? = null): Boolean {
        return try {
            val before = readServerProbe()
            if (before.pids.isNotEmpty()) {
                terminateServerPids(before.pids)
            }
            clearX11SocketFiles()
            preserveOrClearOwnerAfterServerStop(logger)

            val after = readServerProbe()
            val stopped = after.pids.isEmpty() && !after.socketPresent
            if (stopped) {
                if (before.pids.isNotEmpty()) {
                    logger?.i("[+] Stopped integrated X11 server (PIDs=${before.pids.joinToString(",")})")
                } else {
                    logger?.i("[+] Integrated X11 server already stopped")
                }
            } else {
                logger?.e(
                    "[-] Integrated X11 stop could not be verified" +
                        if (after.pids.isNotEmpty()) " (remaining PIDs=${after.pids.joinToString(",")})" else ""
                )
            }
            stopped
        } catch (e: Exception) {
            logger?.e("[-] Could not stop integrated X11 server: ${e.message}")
            false
        }
    }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                stopIntegratedServerLocked(logger)
            }
        }

    /**
     * Stop the Manager-owned graphical session first, then the host X11 server.
     * The DroidSpaces container deliberately remains running. If the session
     * cannot be stopped while its container is alive, keep X11 up instead of
     * creating a service restart loop against a missing display.
     */
    suspend fun stopX11Session(logger: ContainerLogger? = null): Boolean =
        withContext(Dispatchers.IO) {
            lifecycleMutex.withLock {
                val owner = readOwner()
                if (!owner.isNullOrBlank()) {
                    val (ownerStatus, _) = ContainerManager.getContainerRuntimeStatePublic(owner)
                    when (ownerStatus) {
                        ContainerStatus.STOPPED -> {
                            logger?.i("[+] Display owner $owner is stopped; releasing stale ownership")
                            clearOwner()
                        }
                        ContainerStatus.RUNNING, ContainerStatus.UNKNOWN -> {
                            val sessionStopped = GraphicSessionRuntimeController.stop(owner, logger)
                            if (!sessionStopped) {
                                logger?.e(
                                    "[-] X11 server kept running because the graphical session in $owner could not be stopped safely"
                                )
                                return@withLock false
                            }
                        }
                    }
                }
                stopIntegratedServerLocked(logger)
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

    private suspend fun restoreStoppedContainerAfterFailedStart(
        containerName: String,
        shouldRestore: Boolean,
        logger: ContainerLogger? = null
    ): Pair<ContainerStatus, Int?> {
        var latest = ContainerManager.getContainerRuntimeStatePublic(containerName)
        if (!shouldRestore || latest.first == ContainerStatus.STOPPED) {
            return latest
        }

        logger?.i("[*] Restoring container to its pre-start STOPPED state...")
        ContainerManager.stopContainer(containerName, logger)

        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            latest = ContainerManager.getContainerRuntimeStatePublic(containerName)
            if (latest.first == ContainerStatus.STOPPED) break
            delay(500)
        }

        if (latest.first == ContainerStatus.STOPPED) {
            logger?.i("[+] Container restored to STOPPED after failed X11 start")
        } else {
            logger?.w(
                "[!] Could not confirm container restored to STOPPED; current state is ${latest.first}"
            )
        }
        return latest
    }

    private suspend fun rollbackFailedStart(
        lease: ServerLease,
        containerName: String,
        restoreContainer: Boolean,
        logger: ContainerLogger? = null
    ): Pair<ContainerStatus, Int?> {
        val finalContainerState = restoreStoppedContainerAfterFailedStart(
            containerName = containerName,
            shouldRestore = restoreContainer,
            logger = logger
        )
        rollbackServer(lease, logger)
        return finalContainerState
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
    ): X11SessionStartResult = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            var serverLease: ServerLease? = null
            var initialContainerStatus = ContainerStatus.UNKNOWN
            var containerStartAttempted = false

            try {
                logger?.i("--- Starting Integrated X11 Session ---")
                logger?.i("")

                val ownerConflict = activeOwnerConflict(containerName)
                if (ownerConflict != null) {
                    val message =
                        "Display ${Constants.X11_DISPLAY} is already owned by container $ownerConflict whose runtime is not confirmed stopped"
                    logger?.e("[-] $message")
                    logger?.i("[!] This branch is single-display; stop or verify that graphical container before starting another")
                    return@withLock X11SessionStartResult(
                        state = X11SessionStartState.OWNER_CONFLICT,
                        message = message
                    )
                }

                val initialRuntime = ContainerManager.getContainerRuntimeStatePublic(containerName)
                initialContainerStatus = initialRuntime.first
                logger?.i("[*] Initial container state: ${initialRuntime.first}${initialRuntime.second?.let { " (PID=$it)" } ?: ""}")
                if (initialContainerStatus == ContainerStatus.UNKNOWN) {
                    logger?.w("[!] Initial container state is unknown; rollback will not stop it automatically")
                }

                logger?.i("[*] Preparing container X11 config...")
                val configReady = ContainerConfigManager.ensureManualX11Config(containerName, logger)
                if (!configReady) {
                    val message = "Container X11 config is not ready"
                    logger?.e("[-] $message")
                    return@withLock X11SessionStartResult(
                        state = X11SessionStartState.CONFIG_FAILED,
                        containerStatus = initialRuntime.first,
                        containerPid = initialRuntime.second,
                        message = message
                    )
                }

                logger?.i("")
                val serverResult = startIntegratedServerTrackedLocked(containerName, logger)
                if (serverResult.isFailure) {
                    val message = serverResult.exceptionOrNull()?.message ?: "Integrated X11 failed"
                    logger?.e("[-] Integrated X11 failed: $message")
                    return@withLock X11SessionStartResult(
                        state = X11SessionStartState.SERVER_FAILED,
                        containerStatus = initialRuntime.first,
                        containerPid = initialRuntime.second,
                        message = message
                    )
                }
                val activeServer = serverResult.getOrThrow()
                serverLease = activeServer

                logger?.i("")
                if (initialContainerStatus == ContainerStatus.RUNNING) {
                    logger?.i("[+] Container already running; preserving its lifecycle state")
                } else {
                    logger?.i("[*] Starting container...")
                    containerStartAttempted = true
                    val started = ContainerManager.startContainer(containerName, logger)
                    if (!started) {
                        val (statusAfterFailure, pidAfterFailure) =
                            ContainerManager.getContainerRuntimeStatePublic(containerName)
                        if (statusAfterFailure == ContainerStatus.RUNNING) {
                            logger?.w("[!] Start command reported failure, but container is running")
                        } else if (statusAfterFailure == ContainerStatus.STOPPED) {
                            val message = "Container start failed and runtime is stopped"
                            logger?.e("[-] $message")
                            rollbackServer(activeServer, logger)
                            return@withLock X11SessionStartResult(
                                state = X11SessionStartState.CONTAINER_FAILED,
                                containerStatus = statusAfterFailure,
                                containerPid = pidAfterFailure,
                                serverPid = activeServer.pid,
                                message = message
                            )
                        } else {
                            logger?.w("[!] Start command was inconclusive; confirming runtime state")
                        }
                    }
                }

                logger?.i("[*] Confirming container runtime...")
                val (runtimeStatus, pid) = waitForContainerRuntime(containerName)
                if (runtimeStatus != ContainerStatus.RUNNING) {
                    val message = when (runtimeStatus) {
                        ContainerStatus.STOPPED -> "Container runtime remained stopped"
                        ContainerStatus.UNKNOWN -> "Container runtime did not reach a confirmed running state"
                        ContainerStatus.RUNNING -> error("unreachable")
                    }
                    logger?.e("[-] $message")
                    val finalState = rollbackFailedStart(
                        lease = activeServer,
                        containerName = containerName,
                        restoreContainer = initialContainerStatus == ContainerStatus.STOPPED && containerStartAttempted,
                        logger = logger
                    )
                    return@withLock X11SessionStartResult(
                        state = X11SessionStartState.CONTAINER_FAILED,
                        containerStatus = finalState.first,
                        containerPid = finalState.second,
                        serverPid = activeServer.pid,
                        message = message
                    )
                }
                logger?.i("[+] Container runtime active${if (pid != null) " (PID=$pid)" else ""}")

                logger?.i("[*] Waiting for container command readiness (15s)...")
                val commandReady = waitForContainerCommandReady(containerName)
                if (!commandReady) {
                    val message = "Container command channel did not become ready"
                    logger?.e("[-] $message")
                    val finalState = rollbackFailedStart(
                        lease = activeServer,
                        containerName = containerName,
                        restoreContainer = initialContainerStatus == ContainerStatus.STOPPED && containerStartAttempted,
                        logger = logger
                    )
                    return@withLock X11SessionStartResult(
                        state = X11SessionStartState.CONTAINER_NOT_READY,
                        containerStatus = finalState.first,
                        containerPid = finalState.second,
                        serverPid = activeServer.pid,
                        message = message
                    )
                }

                logger?.i("[+] Container command channel ready")
                logger?.i("[*] Synchronizing configured Graphic Session with ${Constants.X11_DISPLAY}...")
                val graphicSessionReady = GraphicSessionRuntimeController.ensureRunning(containerName, logger)
                if (!graphicSessionReady) {
                    val message = "Configured Graphic Session could not be confirmed active"
                    logger?.e("[-] $message")

                    val sessionStopped = GraphicSessionRuntimeController.stop(containerName, logger)
                    val restoreContainer =
                        initialContainerStatus == ContainerStatus.STOPPED && containerStartAttempted

                    val finalState = if (restoreContainer) {
                        rollbackFailedStart(
                            lease = activeServer,
                            containerName = containerName,
                            restoreContainer = true,
                            logger = logger
                        )
                    } else {
                        val currentState = ContainerManager.getContainerRuntimeStatePublic(containerName)
                        if (sessionStopped) {
                            rollbackServer(activeServer, logger)
                        } else {
                            // The container was already active before this operation and its
                            // managed service could not be stopped. Keep/reserve :0 rather
                            // than removing the display underneath a potentially restarting WM.
                            if (!writeOwner(containerName)) {
                                logger?.w("[!] Could not persist conservative ${Constants.X11_DISPLAY} ownership after Graphic Session failure")
                            }
                            logger?.w("[!] Preserving ${Constants.X11_DISPLAY} because the Graphic Session stop was not confirmed")
                        }
                        currentState
                    }

                    return@withLock X11SessionStartResult(
                        state = X11SessionStartState.GRAPHIC_SESSION_FAILED,
                        containerStatus = finalState.first,
                        containerPid = finalState.second,
                        serverPid = activeServer.pid,
                        message = message
                    )
                }

                if (!writeOwner(containerName)) {
                    logger?.w("[!] Session started, but the persistent ${Constants.X11_DISPLAY} owner marker could not be written")
                }
                logger?.i("[+] X11 output is available in the Screen tab")
                logger?.i("[+] Display: ${Constants.X11_DISPLAY}")
                logger?.i("")
                logger?.i("[+] Integrated X11 session started")

                X11SessionStartResult(
                    state = X11SessionStartState.STARTED,
                    containerStatus = runtimeStatus,
                    containerPid = pid,
                    serverPid = activeServer.pid,
                    message = "Integrated X11 session started"
                )
            } catch (e: Exception) {
                val finalContainerState = serverLease?.let { lease ->
                    rollbackFailedStart(
                        lease = lease,
                        containerName = containerName,
                        restoreContainer = initialContainerStatus == ContainerStatus.STOPPED && containerStartAttempted,
                        logger = logger
                    )
                } ?: ContainerManager.getContainerRuntimeStatePublic(containerName)
                val message = e.message ?: "Unexpected X11 session start error"
                logger?.e("[-] Error: $message")
                X11SessionStartResult(
                    state = X11SessionStartState.CONTAINER_FAILED,
                    containerStatus = finalContainerState.first,
                    containerPid = finalContainerState.second,
                    serverPid = serverLease?.pid,
                    message = message
                )
            }
        }
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            try {
                logger?.i("--- Stopping All ---")

                val owner = readOwner()
                if (!owner.isNullOrBlank()) {
                    val (ownerStatus, _) = ContainerManager.getContainerRuntimeStatePublic(owner)
                    if (ownerStatus != ContainerStatus.STOPPED) {
                        GraphicSessionRuntimeController.stop(owner, logger)
                    }
                }

                stopIntegratedServerLocked(logger)
                val containers = ContainerManager.listContainers()
                for (container in containers) {
                    if (container.isRunning) ContainerManager.stopContainer(container.name, logger)
                }
                clearOwner()
                logger?.i("[+] All stopped")
            } catch (e: Exception) {
                logger?.e("[-] Error: ${e.message}")
            }
        }
    }
}
