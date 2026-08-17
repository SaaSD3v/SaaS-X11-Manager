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

    private fun getProcessPids(processName: String): List<Int> {
        return try {
            Shell.cmd("pidof $processName 2>/dev/null").exec().out
                .flatMap { it.trim().split(Regex("\\s+")) }
                .mapNotNull { it.toIntOrNull() }
                .distinct()
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

    private fun getX0SocketInode(): String? {
        return try {
            val result = Shell.cmd("cat /proc/net/unix 2>/dev/null").exec()
            if (!result.isSuccess) null
            else UnixSocketTableParser.findInode(result.out, Constants.X11_SOCK_FILE)
        } catch (_: Exception) {
            null
        }
    }

    private fun pidOwnsSocketInode(pid: Int, inode: String): Boolean {
        if (pid <= 0 || inode.isBlank()) return false
        return try {
            val result = Shell.cmd("ls -l /proc/$pid/fd 2>/dev/null").exec()
            result.out.any { it.contains("socket:[$inode]") }
        } catch (_: Exception) {
            false
        }
    }

    private fun findX0OwnerPid(candidatePids: List<Int> = getProcessPids("termux-x11")): Int? {
        val inode = getX0SocketInode() ?: return null
        return candidatePids.firstOrNull { pidOwnsSocketInode(it, inode) }
    }

    private fun aliveOwnedLoaderPids(candidatePids: Collection<Int>): List<Int> {
        return ownedLoaderPids
            .filter { it in candidatePids && isPidAlive(it) }
            .distinct()
    }

    suspend fun getLoaderStatus(): LoaderStatus = withContext(Dispatchers.IO) {
        try {
            val r = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
            if (r.isSuccess && r.out.isNotEmpty() && r.out[0].contains("ok")) LoaderStatus.Running
            else LoaderStatus.Stopped
        } catch (_: Exception) { LoaderStatus.Stopped }
    }

    suspend fun getLoaderPid(): Int? = withContext(Dispatchers.IO) {
        val candidates = getProcessPids("termux-x11")
        findX0OwnerPid(candidates) ?: aliveOwnedLoaderPids(candidates).firstOrNull()
    }

    private suspend fun startLoaderTracked(
        logger: ContainerLogger? = null
    ): Result<LoaderLease> = withContext(Dispatchers.IO) {
        var launchAttempted = false
        var pidsBeforeLaunch = emptySet<Int>()
        var capturedPid: Int? = null

        try {
            logger?.i("[*] Preparing X11 environment...")

            val existingStatus = getLoaderStatus()
            if (existingStatus == LoaderStatus.Running) {
                val existingPid = getLoaderPid()
                logger?.i(
                    if (existingPid != null) "[+] Reusing X11 loader for :0 (PID=$existingPid)"
                    else "[+] Reusing active X11 loader for :0"
                )
                return@withContext Result.success(
                    LoaderLease(pid = existingPid, ownedPids = emptyList(), reused = true)
                )
            }

            val existingProcessPids = getProcessPids("termux-x11")
            val staleOwnedPids = aliveOwnedLoaderPids(existingProcessPids)
            if (staleOwnedPids.isNotEmpty()) {
                killPids(staleOwnedPids)
                logger?.i("[+] Stopped stale app-owned X0 loader PIDs=${staleOwnedPids.joinToString(",")}")
            }
            ownedLoaderPids = emptySet()

            clearX11SocketFiles()
            Shell.cmd("mkdir -p '${Constants.X11_SOCK_DIR}' 2>/dev/null").exec()

            pidsBeforeLaunch = getProcessPids("termux-x11").toSet()
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

            logger?.i("[*] Waiting for socket (10s)...")
            var wait = 0
            var socketReady = false
            while (wait < 10) {
                delay(1000)
                wait++
                val r = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
                if (r.isSuccess && r.out.any { it.contains("ok") }) {
                    socketReady = true
                    break
                }
            }

            val currentPids = getProcessPids("termux-x11")
            val ownedPids = currentPids.filter { it !in pidsBeforeLaunch }.toMutableList()
            if (capturedPid != null && isPidAlive(capturedPid) && capturedPid !in ownedPids) {
                ownedPids.add(capturedPid)
            }

            if (socketReady) {
                val x0OwnerPid = findX0OwnerPid(currentPids)

                if (x0OwnerPid != null && x0OwnerPid !in ownedPids) {
                    killPids(ownedPids)
                    ownedLoaderPids = emptySet()
                    logger?.i("[+] Reusing X11 loader for :0 (PID=$x0OwnerPid)")
                    return@withContext Result.success(
                        LoaderLease(pid = x0OwnerPid, ownedPids = emptyList(), reused = true)
                    )
                }

                val distinctOwnedPids = ownedPids.distinct()
                val pid = when {
                    x0OwnerPid != null -> x0OwnerPid
                    capturedPid != null && isPidAlive(capturedPid) -> capturedPid
                    distinctOwnedPids.isNotEmpty() -> distinctOwnedPids.first()
                    else -> null
                }

                ownedLoaderPids = distinctOwnedPids.toSet()
                logger?.i("[+] X11 loader active for :0 (PID=${pid ?: "unknown"})")
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
                logger?.e("[-] Socket X0 not created")
                Result.failure(Exception("Socket X0 not created"))
            }
        } catch (e: Exception) {
            if (launchAttempted) {
                val currentPids = getProcessPids("termux-x11")
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
            val candidates = getProcessPids("termux-x11")
            val x0OwnerPid = findX0OwnerPid(candidates)
            val rememberedPids = aliveOwnedLoaderPids(candidates)
            val targets = (listOfNotNull(x0OwnerPid) + rememberedPids).distinct()

            if (targets.isNotEmpty()) {
                killPids(targets)
                logger?.i("[+] Stopped X0 loader (PIDs=${targets.joinToString(",")})")
            } else if (getLoaderStatus() == LoaderStatus.Running) {
                logger?.w("[!] X0 socket exists but its owning PID could not be resolved")
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

            logger?.i("[*] Opening Termux:X11...")
            if (!openTermuxX11()) {
                logger?.w("[!] Could not open Termux:X11 activity")
            }

            logger?.i("")
            if (runtimeStatus == ContainerStatus.RUNNING) {
                logger?.i("[+] Session started")
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
