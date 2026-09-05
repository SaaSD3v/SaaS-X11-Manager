package com.saas.x11manager.util

import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class X11ServerStatus { Running, Stopped }

/** Owns the Manager's fixed integrated X11 server on display :0. */
object X11SessionManager {

    private data class ServerLease(val pid: Int, val reused: Boolean)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun parsePids(lines: List<String>): List<Int> = lines
        .flatMap { it.trim().split(Regex("\\s+")) }
        .mapNotNull(String::toIntOrNull)
        .filter { it > 0 }
        .distinct()

    private fun liveServerPids(): List<Int> = try {
        val pidof = Shell.cmd("pidof ${shellQuote(Constants.X11_SERVER_PROCESS)} 2>/dev/null").exec()
        parsePids(pidof.out).ifEmpty {
            val target = shellQuote(Constants.X11_SERVER_PROCESS)
            val result = Shell.cmd(
                "target=$target; " +
                    "for comm in /proc/[0-9]*/comm; do " +
                    "[ -r \"\$comm\" ] || continue; " +
                    "IFS= read -r name < \"\$comm\" || continue; " +
                    "[ \"\$name\" = \"\$target\" ] || continue; " +
                    "pid=\${comm#/proc/}; pid=\${pid%/comm}; " +
                    "printf '%s\\n' \"\$pid\"; done"
            ).exec()
            parsePids(result.out)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun socketReady(): Boolean = try {
        Shell.cmd("test -S ${shellQuote(Constants.X11_SOCK_FILE)}").exec().isSuccess
    } catch (_: Exception) {
        false
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

    suspend fun getServerStatus(): X11ServerStatus = withContext(Dispatchers.IO) {
        if (socketReady() && liveServerPids().isNotEmpty()) X11ServerStatus.Running
        else X11ServerStatus.Stopped
    }

    suspend fun getServerPid(): Int? = withContext(Dispatchers.IO) {
        if (socketReady()) liveServerPids().firstOrNull() else null
    }

    suspend fun getOwnerContainerName(): String? = withContext(Dispatchers.IO) {
        ContainerManager.listContainers()
            .firstOrNull { it.isRunning && ContainerConfigManager.usesManagedX11(it.bindMounts) }
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
    ): Result<ServerLease> = withContext(Dispatchers.IO) {
        try {
            val existingPids = liveServerPids()
            if (socketReady() && existingPids.isNotEmpty()) {
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
                val pids = liveServerPids()
                if (socketReady() && pids.isNotEmpty()) {
                    val pid = launchedPid?.takeIf { it in pids } ?: pids.first()
                    logger?.i("[+] Integrated X11 ${Constants.X11_DISPLAY} ready (PID=$pid)")
                    return@withContext Result.success(ServerLease(pid, reused = false))
                }
                delay(250)
            }

            killPids(liveServerPids())
            clearSocketState()
            Result.failure(IllegalStateException("Integrated X11 did not create ${Constants.X11_SOCK_FILE}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startIntegratedServer(
        containerName: String? = null,
        logger: ContainerLogger? = null
    ): Result<Int> = startServer(containerName, logger).map { it.pid }

    suspend fun stopIntegratedServer(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val pids = liveServerPids()
            killPids(pids)
            clearSocketState()
            delay(50)
            val stopped = liveServerPids().isEmpty() && !socketReady()
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
    }

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

    private suspend fun conflictingContainer(containerName: String): String? =
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
        var lease: ServerLease? = null
        var containerStartAccepted = false

        try {
            logger?.i("--- Starting Integrated X11 Session ---")

            val before = ContainerManager.getContainerInfo(containerName) ?: run {
                logger?.e("[-] Container $containerName was not found")
                return@withContext false
            }

            conflictingContainer(containerName)?.let { owner ->
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
        val info = ContainerManager.getContainerInfo(containerName)
        val ownsX0 = info?.isRunning == true && ContainerConfigManager.hasAnyX11SocketBind(info.bindMounts)
        if (!ContainerManager.stopContainer(containerName, logger)) return@withContext false
        if (ownsX0) stopIntegratedServer(logger)
        true
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        ContainerManager.listContainers()
            .filter { it.isRunning }
            .forEach { ContainerManager.stopContainer(it.name, logger) }
        stopIntegratedServer(logger)
    }
}
