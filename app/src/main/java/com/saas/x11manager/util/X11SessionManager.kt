package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class LoaderStatus { Running, Stopped }

/** Owns the project X11 server process, socket, XKB bootstrap and container session startup. */
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

    private fun parsePsPids(lines: List<String>): List<Int> = lines
        .mapNotNull { line ->
            line.trim()
                .split(Regex("\\s+"))
                .getOrNull(1)
                ?.toIntOrNull()
        }
        .filter { it > 0 }
        .distinct()

    private fun getProcessPids(processName: String): List<Int> {
        return try {
            val pidof = Shell.cmd("pidof $processName 2>/dev/null").exec()
            val pidofPids = parsePids(pidof.out)
            if (pidofPids.isNotEmpty()) return pidofPids

            val ps = Shell.cmd(
                "ps -ef 2>/dev/null | grep '$processName' | grep -v grep"
            ).exec()
            parsePsPids(ps.out)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isPidAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            Shell.cmd("test -d /proc/$pid 2>/dev/null").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun getLiveServerPids(): List<Int> =
        getProcessPids(Constants.X11_SERVER_PROCESS)
            .filter(::isPidAlive)
            .distinct()

    private fun hasX0Socket(): Boolean {
        return try {
            Shell.cmd("test -S ${shellQuote(Constants.X11_SOCK_FILE)}").exec().isSuccess
        } catch (_: Exception) {
            false
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

    internal fun buildIntegratedServerCommand(apkPath: String): String =
        "TMPDIR=${shellQuote(Constants.INTEGRATED_X11_RUNTIME_DIR)} " +
            "XKB_CONFIG_ROOT=${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} " +
            "CLASSPATH=${shellQuote(apkPath)} " +
            "/system/bin/app_process -Xnoimage-dex2oat / " +
            "--nice-name=${Constants.X11_SERVER_PROCESS} " +
            "com.termux.x11.CmdEntryPoint ${Constants.X11_DISPLAY} " +
            ">${shellQuote(Constants.X11_LOG_FILE)} 2>&1 & echo ${'$'}!"

    suspend fun getLoaderStatus(): LoaderStatus = withContext(Dispatchers.IO) {
        if (hasX0Socket() && getLiveServerPids().isNotEmpty()) {
            LoaderStatus.Running
        } else {
            LoaderStatus.Stopped
        }
    }

    suspend fun getLoaderPid(): Int? = withContext(Dispatchers.IO) {
        getLiveServerPids().firstOrNull()
    }

    private suspend fun startIntegratedServerTracked(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<ServerLease> = withContext(Dispatchers.IO) {
        try {
            val liveBefore = getLiveServerPids()
            if (hasX0Socket() && liveBefore.isNotEmpty()) {
                val pid = liveBefore.first()
                logger?.i("[+] Reusing integrated X11 server ${Constants.X11_DISPLAY} (PID=$pid)")
                return@withContext Result.success(ServerLease(pid = pid, reused = true))
            }

            if (liveBefore.isNotEmpty()) {
                logger?.w("[!] Stale SaaS X11 process found without X0 socket; restarting it")
                killPids(liveBefore)
            }
            if (hasX0Socket()) {
                logger?.w("[!] Stale integrated X0 socket found; replacing it")
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

            logger?.i("[*] Starting integrated X11 server ${Constants.X11_DISPLAY}...")
            val launch = Shell.cmd(buildIntegratedServerCommand(apkPath)).exec()
            val capturedPid = launch.out.asReversed()
                .firstNotNullOfOrNull { it.trim().toIntOrNull() }

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                val live = getLiveServerPids()
                if (hasX0Socket() && live.isNotEmpty()) {
                    val pid = when {
                        capturedPid != null && capturedPid in live -> capturedPid
                        else -> live.first()
                    }
                    logger?.i("[+] Integrated X11 server ready (PID=$pid)")
                    logger?.i("[+] X11 socket: ${Constants.X11_SOCK_FILE}")
                    return@withContext Result.success(ServerLease(pid = pid, reused = false))
                }
                delay(250)
            }

            val liveAfter = getLiveServerPids()
            killPids(liveAfter)
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
    ): Result<Int> = startIntegratedServerTracked(containerName, logger).map { it.pid }

    private suspend fun rollbackServer(lease: ServerLease, logger: ContainerLogger? = null) {
        if (lease.reused) return
        killPids(listOf(lease.pid))
        clearX11SocketFiles()
        logger?.i("[+] Rolled back integrated X11 server after failed session start")
    }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val livePids = getLiveServerPids()
                if (livePids.isNotEmpty()) {
                    killPids(livePids)
                    logger?.i("[+] Stopped integrated X11 server (PIDs=${livePids.joinToString(",")})")
                }
                clearX11SocketFiles()
                true
            } catch (e: Exception) {
                logger?.e("[-] Could not stop integrated X11 server: ${e.message}")
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
    ) = withContext(Dispatchers.IO) {
        var serverLease: ServerLease? = null
        var containerStartAccepted = false

        try {
            logger?.i("--- Starting Integrated X11 Session ---")
            logger?.i("")

            logger?.i("[*] Preparing container X11 config...")
            val configReady = ContainerConfigManager.ensureManualX11Config(containerName, logger)
            if (!configReady) {
                logger?.e("[-] Container X11 config is not ready")
                return@withContext
            }

            logger?.i("")
            val serverResult = startIntegratedServerTracked(containerName, logger)
            if (serverResult.isFailure) {
                logger?.e("[-] Integrated X11 failed: ${serverResult.exceptionOrNull()?.message}")
                return@withContext
            }
            val activeServer = serverResult.getOrThrow()
            serverLease = activeServer

            logger?.i("")
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
                    return@withContext
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

            logger?.i("[+] X11 output is available in the Screen tab")
            logger?.i("")
            if (runtimeStatus == ContainerStatus.RUNNING && commandReady) {
                logger?.i("[+] Integrated X11 session started")
            } else {
                logger?.w("[!] X11 server is ready while container startup is still settling")
            }
        } catch (e: Exception) {
            if (!containerStartAccepted) {
                serverLease?.let { rollbackServer(it, logger) }
            }
            logger?.e("[-] Error: ${e.message}")
        }
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            logger?.i("--- Stopping All ---")
            stopIntegratedServer(logger)
            val containers = ContainerManager.listContainers()
            for (container in containers) {
                if (container.isRunning) ContainerManager.stopContainer(container.name, logger)
            }
            logger?.i("[+] All stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
