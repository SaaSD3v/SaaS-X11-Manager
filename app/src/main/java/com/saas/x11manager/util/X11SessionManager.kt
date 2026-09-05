package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class X11ServerStatus { Running, Stopped }

/**
 * Owns one integrated X11 server only: display :0.
 *
 * X11-0nly intentionally has no display allocator, monitor slots, per-display
 * runtime directories or display switching. At most one running container may
 * consume the Manager-owned X0 transport at a time.
 */
object X11SessionManager {

    private data class ServerLease(
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
                    "printf '%s\\n' \"\$pid\"; done"
            ).exec()
            parsePids(proc.out)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getLiveServerPids(): List<Int> =
        getProcessPids(Constants.X11_SERVER_PROCESS).distinct()

    private fun hasX0Socket(): Boolean = try {
        Shell.cmd("test -S ${shellQuote(Constants.X11_SOCK_FILE)}").exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun prepareRuntimeDirectory(): Boolean = try {
        Shell.cmd(
            "mkdir -p ${shellQuote(Constants.X11_SOCK_DIR)} && " +
                "chmod 1777 ${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
                "${shellQuote(Constants.X11_SOCK_DIR)}"
        ).exec().isSuccess
    } catch (_: Exception) {
        false
    }

    private fun hasCachedXkbConfig(): Boolean {
        val root = shellQuote(Constants.INTEGRATED_X11_XKB_DIR)
        return try {
            Shell.cmd("test -d $root/rules && test -d $root/symbols && test -d $root/keycodes").exec().isSuccess
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

        logger?.e("[-] XKB configuration was not found in $containerName; expected /usr/share/X11/xkb")
        return false
    }

    private fun clearX11SocketFiles() {
        Shell.cmd("rm -f ${shellQuote(Constants.X11_SOCK_FILE)} 2>/dev/null").exec()
        Shell.cmd("rm -f ${shellQuote(Constants.X11_LOCK_FILE)} 2>/dev/null").exec()
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

    internal fun buildIntegratedServerCommand(apkPath: String): String =
        "TMPDIR=${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
            "XKB_CONFIG_ROOT=${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} " +
            "CLASSPATH=${shellQuote(apkPath)} " +
            "/system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=${Constants.X11_SERVER_PROCESS} " +
            "com.termux.x11.CmdEntryPoint ${Constants.X11_DISPLAY} " +
            ">${shellQuote(Constants.X11_LOG_FILE)} 2>&1 & echo ${'$'}!"

    suspend fun getServerStatus(): X11ServerStatus = withContext(Dispatchers.IO) {
        if (hasX0Socket() && getLiveServerPids().isNotEmpty()) X11ServerStatus.Running
        else X11ServerStatus.Stopped
    }

    suspend fun getServerPid(): Int? = withContext(Dispatchers.IO) {
        if (!hasX0Socket()) null else getLiveServerPids().firstOrNull()
    }

    suspend fun getOwnerContainerName(): String? = withContext(Dispatchers.IO) {
        ContainerManager.listContainers()
            .firstOrNull { it.isRunning && ContainerConfigManager.usesManagedX11(it.bindMounts) }
            ?.name
    }

    suspend fun ensureContainerGraphicSession(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.ensureRunning(
        containerName = containerName,
        logger = logger
    )

    suspend fun stopContainerGraphicSession(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = GraphicSessionRuntimeController.stop(containerName, logger)

    private suspend fun startIntegratedServerTracked(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<ServerLease> = withContext(Dispatchers.IO) {
        val operationStartedAt = System.nanoTime()
        try {
            logger?.i("--- Integrated X11 Server Start ---")
            logger?.i("[CTX] Display: ${Constants.X11_DISPLAY}")
            logger?.i("[CTX] Process: ${Constants.X11_SERVER_PROCESS}")
            logger?.i("[CTX] Runtime: ${Constants.INTEGRATED_X11_RUNTIME_DIR}")
            logger?.i("[CTX] Socket: ${Constants.X11_SOCK_FILE}")

            val liveBefore = getLiveServerPids()
            val socketBefore = hasX0Socket()
            logger?.i("[CTX] Existing server PIDs: ${formatPids(liveBefore)}")
            logger?.i("[CTX] Existing X0 socket: ${if (socketBefore) "present" else "absent"}")

            if (socketBefore && liveBefore.isNotEmpty()) {
                val pid = liveBefore.first()
                logger?.i("[+] Reusing integrated X11 ${Constants.X11_DISPLAY} (PID=$pid)")
                return@withContext Result.success(ServerLease(pid, reused = true))
            }

            if (liveBefore.isNotEmpty()) {
                logger?.w("[!] Stale ${Constants.X11_SERVER_PROCESS} process found without X0; restarting it")
                killPids(liveBefore)
            }
            clearX11SocketFiles()

            if (!prepareRuntimeDirectory()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not prepare integrated X11 runtime directory")
                )
            }

            if (!hasCachedXkbConfig()) {
                if (containerName.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Integrated X11 needs XKB data from a configured container before its first start")
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

            logger?.i("[*] Starting integrated X11 ${Constants.X11_DISPLAY}...")
            val launchStartedAt = System.nanoTime()
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath)).exec()
            val capturedPid = launch.out.asReversed().firstNotNullOfOrNull { it.trim().toIntOrNull() }

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                val live = getLiveServerPids()
                if (hasX0Socket() && live.isNotEmpty()) {
                    val pid = if (capturedPid != null && capturedPid in live) capturedPid else live.first()
                    logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} ready (PID=$pid)")
                    logger?.i("[CTX] Server readiness: ${(System.nanoTime() - launchStartedAt) / 1_000_000L}ms")
                    logger?.i("[CTX] Total start duration: ${(System.nanoTime() - operationStartedAt) / 1_000_000L}ms")
                    return@withContext Result.success(ServerLease(pid, reused = false))
                }
                delay(250)
            }

            val liveAfter = getLiveServerPids()
            killPids(liveAfter)
            clearX11SocketFiles()
            Result.failure(
                IllegalStateException(
                    "Integrated X11 did not create ${Constants.X11_SOCK_FILE}; see ${Constants.X11_LOG_FILE}"
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
    ): Result<Int> = startIntegratedServerTracked(containerName, logger).map { it.pid }

    private suspend fun rollbackServer(lease: ServerLease, logger: ContainerLogger? = null) {
        if (lease.reused) return
        killPids(listOf(lease.pid))
        clearX11SocketFiles()
        logger?.i("[+] Rolled back newly-created integrated X11 server")
    }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val livePids = getLiveServerPids()
            if (livePids.isNotEmpty()) killPids(livePids)
            clearX11SocketFiles()
            delay(50)

            val remaining = getLiveServerPids()
            val socketAfter = hasX0Socket()
            if (remaining.isNotEmpty() || socketAfter) {
                logger?.e("[-] Could not fully stop integrated X11 ${Constants.X11_DISPLAY}")
                false
            } else {
                if (livePids.isNotEmpty()) {
                    logger?.i("[+] Stopped integrated X11 ${Constants.X11_DISPLAY} (PIDs=${livePids.joinToString(",")})")
                } else {
                    logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} was already stopped")
                }
                true
            }
        } catch (e: Exception) {
            logger?.e("[-] Could not stop integrated X11 ${Constants.X11_DISPLAY}: ${e.message}")
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

    private suspend fun anotherRunningX11Container(containerName: String): String? =
        ContainerManager.listContainers()
            .firstOrNull {
                it.isRunning &&
                    it.name != containerName &&
                    ContainerConfigManager.hasAnyX11SocketBind(it.bindMounts)
            }
            ?.name

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        var serverLease: ServerLease? = null
        var containerStartAccepted = false

        try {
            logger?.i("--- Starting Integrated X11 Session ---")
            logger?.i("[CTX] Display: ${Constants.X11_DISPLAY}")

            val before = ContainerManager.getContainerInfo(containerName) ?: run {
                logger?.e("[-] Container $containerName was not found")
                return@withContext false
            }

            anotherRunningX11Container(containerName)?.let { owner ->
                logger?.e("[-] Integrated X11 ${Constants.X11_DISPLAY} is already attached to running container $owner")
                logger?.e("[-] Stop that X11 container before starting another one")
                return@withContext false
            }

            val wasRunning = before.isRunning
            if (wasRunning) {
                if (!ContainerConfigManager.usesManagedX11(before.bindMounts)) {
                    logger?.e("[-] Running container $containerName is not attached to the fixed X0 transport")
                    logger?.e("[-] Stop it once so X11-0nly can rewrite its bind to ${Constants.X11_SOCK_DIR}")
                    return@withContext false
                }
            } else {
                if (!ContainerConfigManager.ensureManualX11Config(containerName, logger)) {
                    logger?.e("[-] Container X11 config is not ready")
                    return@withContext false
                }
            }

            val serverResult = startIntegratedServerTracked(containerName, logger)
            if (serverResult.isFailure) {
                logger?.e("[-] Integrated X11 failed: ${serverResult.exceptionOrNull()?.message}")
                return@withContext false
            }
            val activeServer = serverResult.getOrThrow()
            serverLease = activeServer

            if (wasRunning) {
                containerStartAccepted = true
                logger?.i("[+] Container already running on ${Constants.X11_DISPLAY}")
            } else {
                logger?.i("[*] Starting container...")
                val started = ContainerManager.startContainer(containerName, logger)
                if (started) {
                    containerStartAccepted = true
                } else {
                    val (statusAfterFailure, _) = ContainerManager.getContainerRuntimeStatePublic(containerName)
                    if (statusAfterFailure == ContainerStatus.RUNNING) {
                        containerStartAccepted = true
                        logger?.w("[!] Start command reported failure, but container is running")
                    } else if (statusAfterFailure == ContainerStatus.STOPPED) {
                        logger?.e("[-] Container start failed and runtime is stopped")
                        rollbackServer(activeServer, logger)
                        return@withContext false
                    }
                }
            }

            val (runtimeStatus, pid) = waitForContainerRuntime(containerName)
            if (runtimeStatus == ContainerStatus.RUNNING) {
                logger?.i("[+] Container runtime active${if (pid != null) " (PID=$pid)" else ""}")
            }

            val commandReady = waitForContainerCommandReady(containerName)
            if (!commandReady) {
                logger?.w("[!] Container command channel did not become ready")
                return@withContext false
            }
            logger?.i("[+] Container command channel ready")

            val graphicSessionReady = ensureContainerGraphicSession(containerName, logger)
            if (!graphicSessionReady) {
                logger?.w("[!] ${Constants.X11_DISPLAY} is ready, but the configured graphic session is not active")
                return@withContext false
            }

            if (runtimeStatus != ContainerStatus.RUNNING) {
                logger?.w("[!] Container runtime was not confirmed running")
                return@withContext false
            }

            logger?.i("[+] Integrated X11 session started on ${Constants.X11_DISPLAY}")
            true
        } catch (e: Exception) {
            if (!containerStartAccepted) serverLease?.let { rollbackServer(it, logger) }
            logger?.e("[-] Error: ${e.message}")
            false
        }
    }

    suspend fun stopX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Stopping Container X11 Session ---")
        val before = ContainerManager.getContainerInfo(containerName)
        val usedX11 = before?.isRunning == true && ContainerConfigManager.hasAnyX11SocketBind(before.bindMounts)

        val stopped = ContainerManager.stopContainer(containerName, logger)
        if (!stopped) {
            logger?.e("[-] Container stop was not confirmed; integrated X11 will be left untouched")
            return@withContext false
        }

        if (usedX11) {
            val serverStopped = stopIntegratedServer(logger)
            if (!serverStopped) {
                logger?.w("[!] Container stopped, but ${Constants.X11_DISPLAY} cleanup was not fully confirmed")
            }
        }
        true
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            val runningContainers = ContainerManager.listContainers().filter { it.isRunning }
            for (container in runningContainers) {
                ContainerManager.stopContainer(container.name, logger)
            }
            stopIntegratedServer(logger)
            logger?.i("[+] All containers and integrated X11 ${Constants.X11_DISPLAY} stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
