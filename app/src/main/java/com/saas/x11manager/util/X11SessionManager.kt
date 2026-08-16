package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LoaderStatus { Running, Stopped }

object X11SessionManager {

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

    suspend fun getLoaderStatus(): LoaderStatus = withContext(Dispatchers.IO) {
        try {
            val r = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
            if (r.isSuccess && r.out.isNotEmpty() && r.out[0].contains("ok")) LoaderStatus.Running
            else LoaderStatus.Stopped
        } catch (_: Exception) { LoaderStatus.Stopped }
    }

    suspend fun getLoaderPid(): Int? = withContext(Dispatchers.IO) {
        getProcessPids("termux-x11").firstOrNull()
    }

    suspend fun startLoader(logger: ContainerLogger? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            logger?.i("[*] Preparing X11 environment...")

            val existingStatus = getLoaderStatus()
            val existingPid = getLoaderPid()
            if (existingStatus == LoaderStatus.Running && existingPid != null && existingPid > 0) {
                logger?.i("[+] Reusing X11 loader (PID=$existingPid)")
                return@withContext Result.success(existingPid)
            }

            val stalePids = getProcessPids("termux-x11")
            if (stalePids.isNotEmpty()) {
                Shell.cmd("kill -9 ${stalePids.joinToString(" ")} 2>/dev/null").exec()
                logger?.i("[+] Killed stale loader PIDs=${stalePids.joinToString(",")}")
            }

            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/X* 2>/dev/null").exec()
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/*-lock 2>/dev/null").exec()
            Shell.cmd("mkdir -p '${Constants.X11_SOCK_DIR}' 2>/dev/null").exec()

            logger?.i("[*] Starting X11 loader...")

            Shell.cmd(
                "CLASSPATH=${Constants.LOADER_APK} /system/bin/app_process " +
                    "-Xnoimage-dex2oat / " +
                    "--nice-name=termux-x11 com.termux.x11.Loader :0 &"
            ).exec()

            logger?.i("[*] Waiting for socket (10s)...")
            var wait = 0
            while (wait < 10) {
                Thread.sleep(1000)
                wait++
                val r = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
                if (r.isSuccess && r.out.isNotEmpty() && r.out[0].contains("ok")) break
            }

            val r = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
            if (r.isSuccess && r.out.isNotEmpty() && r.out[0].contains("ok")) {
                val pid = getLoaderPid() ?: 0
                logger?.i("[+] X11 loader active (PID=$pid)")
                Result.success(pid)
            } else {
                logger?.e("[-] Socket X0 not created")
                Result.failure(Exception("Socket X0 not created"))
            }
        } catch (e: Exception) {
            logger?.e("[-] Loader error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun stopLoader(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val pids = getProcessPids("termux-x11")
            if (pids.isNotEmpty()) {
                Shell.cmd("kill -9 ${pids.joinToString(" ")} 2>/dev/null").exec()
                logger?.i("[+] Stopped loader (PIDs=${pids.joinToString(",")})")
            }
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/X* 2>/dev/null").exec()
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/*-lock 2>/dev/null").exec()
            true
        } catch (_: Exception) { false }
    }

    suspend fun openTermuxX11(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("am start -n ${Constants.TERMUX_X11_PACKAGE}/.MainActivity 2>/dev/null").exec().isSuccess
        } catch (_: Exception) { false }
    }

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ) = withContext(Dispatchers.IO) {
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

            val loaderResult = startLoader(logger)
            loaderResult.onFailure { e ->
                logger?.e("[-] Loader failed: ${e.message}")
                return@withContext
            }

            logger?.i("")
            logger?.i("[*] Starting container...")
            val started = ContainerManager.startContainer(containerName, logger)
            if (!started) {
                logger?.e("[-] Container start failed")
                return@withContext
            }

            logger?.i("[*] Waiting for boot (15s)...")
            var wait = 0
            while (wait < 15) {
                Thread.sleep(1000)
                wait++
                val (isRunning, _) = ContainerManager.checkContainerStatusPublic(containerName)
                if (isRunning) {
                    logger?.i("[+] Container ready (${wait}s)")
                    break
                }
            }

            val (isRunning, _) = ContainerManager.checkContainerStatusPublic(containerName)
            if (!isRunning) logger?.w("[!] Container may still be booting")

            logger?.i("[*] Opening Termux:X11...")
            openTermuxX11()

            logger?.i("")
            logger?.i("[+] Session started")
        } catch (e: Exception) {
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
