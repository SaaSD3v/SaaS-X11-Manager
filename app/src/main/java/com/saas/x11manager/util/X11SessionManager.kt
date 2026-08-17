package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class LoaderStatus { Running, Stopped }

object X11SessionManager {

    private data class LoaderLease(
        val pid: Int?,
        val ownedPids: List<Int>,
        val reused: Boolean
    )

    private var ownedLoaderPids: Set<Int> = emptySet()

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

    private fun getLiveLoaderPids(): List<Int> =
        getProcessPids("termux-x11").filter(::isPidAlive).distinct()

    private fun hasX0Socket(): Boolean {
        return try {
            Shell.cmd("test -S '${Constants.X11_SOCK_FILE}'").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun clearX11SocketFiles() {
        Shell.cmd("rm -f '${Constants.X11_SOCK_FILE}' 2>/dev/null").exec()
        Shell.cmd("rm -f '${Constants.TERMUX_PREFIX}/tmp/.X0-lock' 2>/dev/null").exec()
    }

    private fun killPids(pids: Collection<Int>) {
        val targets = pids.filter { it > 0 }.distinct()
        if (targets.isNotEmpty()) {
            Shell.cmd("kill -9 ${targets.joinToString(" ")} 2>/dev/null").exec()
        }
    }

    private fun aliveOwnedLoaderPids(candidatePids: Collection<Int>): List<Int> {
        return ownedLoaderPids
            .filter { it in candidatePids && isPidAlive(it) }
            .distinct()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    suspend fun getLoaderStatus(): LoaderStatus = withContext(Dispatchers.IO) {
        if (hasX0Socket() && getLiveLoaderPids().isNotEmpty()) {
            LoaderStatus.Running
        } else {
            LoaderStatus.Stopped
        }
    }

    suspend fun getLoaderPid(): Int? = withContext(Dispatchers.IO) {
        getLiveLoaderPids().firstOrNull()
    }

    private suspend fun startLoaderTracked(
        logger: ContainerLogger? = null
    ): Result<LoaderLease> = withContext(Dispatchers.IO) {
        var launchAttempted = false
        var pidsBeforeLaunch = emptySet<Int>()
        var capturedPid: Int? = null

        try {
            logger?.i("[*] Preparing X11 environment...")

            val socketExists = hasX0Socket()
            val livePids = getLiveLoaderPids()
            if (socketExists && livePids.isNotEmpty()) {
                val existingPid = livePids.first()
                logger?.i("[+] Reusing X11 loader for :0 (PID=$existingPid)")
                return@withContext Result.success(
                    LoaderLease(pid = existingPid, ownedPids = emptyList(), reused = true)
                )
            }

            if (socketExists) {
                logger?.w("[!] Stale X0 socket found without a live termux-x11 process")
                clearX11SocketFiles()
            }

            val existingProcessPids = getLiveLoaderPids()
            val staleOwnedPids = aliveOwnedLoaderPids(existingProcessPids)
            if (staleOwnedPids.isNotEmpty()) {
                killPids(staleOwnedPids)
                logger?.i("[+] Stopped stale app-owned X11 loader PIDs=${staleOwnedPids.joinToString(",")}")
            }
            ownedLoaderPids = emptySet()

            clearX11SocketFiles()
            Shell.cmd("mkdir -p '${Constants.X11_SOCK_DIR}' 2>/dev/null").exec()

            pidsBeforeLaunch = getLiveLoaderPids().toSet()
            logger?.i("[*] Starting X11 loader...")

            val launch = Shell.cmd(
                "CLASSPATH=${Constants.LOADER_APK} /system/bin/app_process " +
                    "-Xnoimage-dex2oat / " +
                    "--nice-name=termux-x11 com.termux.x11.Loader :0 " +
                    ">/dev/null 2>&1 & echo ${'$'}!"
            ).exec()
            launchAttempted = true
            capturedPid = launch.out.asReversed()
                .firstNotNullOfOrNull { it.trim().toIntOrNull() }

            logger?.i("[*] Waiting for live Loader + X0 socket (10s)...")
            var wait = 0
            var loaderReady = false
            while (wait < 10) {
                delay(1000)
                wait++
                if (hasX0Socket() && getLiveLoaderPids().isNotEmpty()) {
                    loaderReady = true
                    break
                }
            }

            val currentPids = getLiveLoaderPids()
            val ownedPids = currentPids.filter { it !in pidsBeforeLaunch }.toMutableList()
            if (capturedPid != null && isPidAlive(capturedPid) && capturedPid !in ownedPids) {
                ownedPids.add(capturedPid)
            }

            if (loaderReady) {
                val distinctOwnedPids = ownedPids.distinct()
                if (distinctOwnedPids.isEmpty()) {
                    val reusedPid = currentPids.first()
                    logger?.i("[+] Reusing X11 loader for :0 (PID=$reusedPid)")
                    return@withContext Result.success(
                        LoaderLease(pid = reusedPid, ownedPids = emptyList(), reused = true)
                    )
                }

                val pid = when {
                    capturedPid != null && isPidAlive(capturedPid) -> capturedPid
                    else -> distinctOwnedPids.first()
                }

                ownedLoaderPids = distinctOwnedPids.toSet()
                logger?.i("[+] X11 loader active for :0 (PID=$pid)")
                Result.success(
                    LoaderLease(
                        pid = pid,
                        ownedPids = distinctOwnedPids,
                        reused = false
                    )
                )
            } else {
                killPids(ownedPids)
                ownedLoaderPids = emptySet()
                clearX11SocketFiles()
                logger?.e("[-] Live Loader + X0 socket not ready")
                Result.failure(Exception("Live Loader + X0 socket not ready"))
            }
        } catch (e: Exception) {
            if (launchAttempted) {
                val currentPids = getLiveLoaderPids()
                val ownedPids = currentPids.filter { it !in pidsBeforeLaunch }.toMutableList()
                if (capturedPid != null && isPidAlive(capturedPid) && capturedPid !in ownedPids) {
                    ownedPids.add(capturedPid)
                }
                killPids(ownedPids)
                ownedLoaderPids = emptySet()
                clearX11SocketFiles()
            }
            logger?.e("[-] Loader error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun startLoader(logger: ContainerLogger? = null): Result<Int> {
        return startLoaderTracked(logger).map { it.pid ?: 0 }
    }

    private suspend fun rollbackLoader(lease: LoaderLease, logger: ContainerLogger? = null) {
        if (lease.reused) return

        killPids(lease.ownedPids)
        ownedLoaderPids = ownedLoaderPids - lease.ownedPids.toSet()
        clearX11SocketFiles()
        logger?.i("[+] Rolled back X11 loader from failed session start")
    }

    suspend fun stopLoader(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val livePids = getLiveLoaderPids()
            val rememberedPids = aliveOwnedLoaderPids(livePids)
            val targets = if (hasX0Socket()) livePids else rememberedPids

            if (targets.isNotEmpty()) {
                killPids(targets)
                logger?.i("[+] Stopped X11 loader (PIDs=${targets.joinToString(",")})")
            } else if (hasX0Socket()) {
                logger?.w("[!] X0 socket exists without a live termux-x11 process")
            }

            ownedLoaderPids = emptySet()
            clearX11SocketFiles()
            true
        } catch (_: Exception) { false }
    }

    suspend fun openTermuxX11(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("am start -n ${Constants.TERMUX_X11_PACKAGE}/.MainActivity 2>/dev/null").exec().isSuccess
        } catch (_: Exception) { false }
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
        var loaderLease: LoaderLease? = null
        var containerStartAccepted = false
        var preserveLoaderOnUncertainState = false

        try {
            logger?.i("--- Starting Session ---")
            logger?.i("")

            logger?.i("[*] Preparing container X11 config...")
            val configReady = ContainerConfigManager.ensureManualX11Config(containerName, logger)
            if (!configReady) {
                logger?.e("[-] Container X11 config is not ready")
                return@withContext
            }
            logger?.i("")

            val loaderResult = startLoaderTracked(logger)
            if (loaderResult.isFailure) {
                logger?.e("[-] Loader failed: ${loaderResult.exceptionOrNull()?.message}")
                return@withContext
            }
            val activeLoaderLease = loaderResult.getOrThrow()
            loaderLease = activeLoaderLease

            logger?.i("")
            logger?.i("[*] Starting container...")
            val started = ContainerManager.startContainer(containerName, logger)
            if (started) {
                containerStartAccepted = true
            } else {
                val (statusAfterFailure, _) =
                    ContainerManager.getContainerRuntimeStatePublic(containerName)

                when (statusAfterFailure) {
                    ContainerStatus.RUNNING -> {
                        containerStartAccepted = true
                        logger?.w("[!] Start command reported failure, but container is running")
                    }
                    ContainerStatus.STOPPED -> {
                        logger?.e("[-] Container start failed and runtime is stopped")
                        rollbackLoader(activeLoaderLease, logger)
                        return@withContext
                    }
                    ContainerStatus.UNKNOWN -> {
                        preserveLoaderOnUncertainState = true
                        logger?.w(
                            "[!] Start command reported failure and runtime status is unknown; " +
                                "preserving X0 while rechecking"
                        )
                    }
                }
            }

            logger?.i("[*] Confirming container runtime...")
            val (runtimeStatus, pid) = waitForContainerRuntime(containerName)
            when (runtimeStatus) {
                ContainerStatus.RUNNING -> {
                    containerStartAccepted = true
                    preserveLoaderOnUncertainState = false
                    logger?.i("[+] Container runtime active${if (pid != null) " (PID=$pid)" else ""}")
                }
                ContainerStatus.STOPPED -> {
                    if (!started && !containerStartAccepted) {
                        logger?.e("[-] Container runtime confirmed stopped")
                        rollbackLoader(activeLoaderLease, logger)
                        return@withContext
                    }
                    logger?.w("[!] Start command was accepted but runtime is currently stopped")
                }
                ContainerStatus.UNKNOWN -> {
                    preserveLoaderOnUncertainState = true
                    logger?.w("[!] Container runtime status remains unknown")
                }
            }

            logger?.i("[*] Waiting for container command readiness (15s)...")
            val commandReady = waitForContainerCommandReady(containerName)
            if (commandReady) {
                logger?.i("[+] Container command channel ready")
            } else {
                logger?.w("[!] Container is not accepting commands yet; X11 init may still be booting")
            }

            logger?.i("[*] Opening Termux:X11...")
            if (!openTermuxX11()) {
                logger?.w("[!] Could not open Termux:X11 activity")
            }

            logger?.i("")
            if (runtimeStatus == ContainerStatus.RUNNING && commandReady) {
                logger?.i("[+] Session started")
            } else if (runtimeStatus == ContainerStatus.RUNNING) {
                logger?.w("[!] X11 opened while container init is still becoming ready")
            } else {
                logger?.w("[!] Session start requested; runtime confirmation is incomplete")
            }
        } catch (e: Exception) {
            if (!containerStartAccepted && !preserveLoaderOnUncertainState) {
                loaderLease?.let { rollbackLoader(it, logger) }
            }
            logger?.e("[-] Error: ${e.message}")
        }
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            logger?.i("--- Stopping All ---")
            stopLoader(logger)
            val containers = ContainerManager.listContainers()
            for (c in containers) {
                if (c.isRunning) ContainerManager.stopContainer(c.name, logger)
            }
            logger?.i("[+] All stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
