package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LoaderStatus { Running, Stopped }

object X11SessionManager {

    suspend fun getLoaderStatus(): LoaderStatus = withContext(Dispatchers.IO) {
        try {
            val pidCheck = Shell.cmd("ps -ef 2>/dev/null | grep 'termux-x11' | grep -v grep | awk '{print \$2}'").exec()
            val sockCheck = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
            if (pidCheck.isSuccess && pidCheck.out.isNotEmpty() &&
                sockCheck.isSuccess && sockCheck.out.isNotEmpty() && sockCheck.out[0].contains("ok")
            ) {
                LoaderStatus.Running
            } else {
                LoaderStatus.Stopped
            }
        } catch (_: Exception) {
            LoaderStatus.Stopped
        }
    }

    suspend fun getLoaderPid(): Int? = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(
                "ps -ef 2>/dev/null | grep 'termux-x11' | grep -v grep | awk '{print \$2}' | head -1"
            ).exec()
            result.out.firstOrNull()?.trim()?.toIntOrNull()
        } catch (_: Exception) { null }
    }

    suspend fun startLoader(logger: ContainerLogger? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            logger?.i("Cleaning old X11 sockets...")
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/X* 2>/dev/null").exec()
            Shell.cmd("mkdir -p '${Constants.X11_SOCK_DIR}' 2>/dev/null").exec()

            logger?.i("Starting X11 Loader as root...")
            Shell.cmd(
                "CLASSPATH=${Constants.LOADER_APK} /system/bin/app_process " +
                "-Xnoimage-dex2oat / " +
                "--nice-name='termux-x11' com.termux.x11.Loader :0 &"
            ).exec()

            logger?.i("Waiting for socket X0 (max 10s)...")
            var wait = 0
            while (wait < 10) {
                Thread.sleep(1000)
                wait++
                val sockCheck = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
                if (sockCheck.isSuccess && sockCheck.out.isNotEmpty() && sockCheck.out[0].contains("ok")) {
                    break
                }
            }

            val sockExists = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
            if (sockExists.isSuccess && sockExists.out.isNotEmpty() && sockExists.out[0].contains("ok")) {
                val pidResult = Shell.cmd(
                    "ps -ef 2>/dev/null | grep 'termux-x11' | grep -v grep | awk '{print \$2}' | head -1"
                ).exec()
                val pid = pidResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
                logger?.i("Loader running (PID=$pid)")
                Result.success(pid)
            } else {
                logger?.e("Socket X0 not created in 10s")
                Result.failure(Exception("Socket X0 not created"))
            }
        } catch (e: Exception) {
            logger?.e("Loader error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun stopLoader(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val pids = Shell.cmd(
                "ps -ef 2>/dev/null | grep 'termux-x11' | grep -v grep | awk '{print \$2}'"
            ).exec()
            if (pids.isSuccess) {
                for (pid in pids.out) {
                    val trimmed = pid.trim()
                    if (trimmed.isNotEmpty()) {
                        Shell.cmd("kill -9 $trimmed 2>/dev/null").exec()
                    }
                }
            }
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/X* 2>/dev/null").exec()
            logger?.i("Loader stopped")
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun openTermuxX11(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd(
                "am start -n ${Constants.TERMUX_X11_PACKAGE}/.MainActivity 2>/dev/null"
            ).exec()
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    suspend fun startX11Session(
        containerName: String,
        logger: ContainerLogger? = null
    ) = withContext(Dispatchers.IO) {
        try {
            logger?.i("=== Starting X11 Session for $containerName ===")

            // Step 1: Start Loader
            logger?.i("Step 1: Starting X11 Loader...")
            val loaderResult = startLoader(logger)
            loaderResult.onFailure { e ->
                logger?.e("Loader failed: ${e.message}")
                return@withContext
            }

            // Step 2: Start container
            logger?.i("Step 2: Starting container...")
            val started = ContainerManager.startContainer(containerName, logger)
            if (!started) {
                logger?.e("Failed to start container")
                return@withContext
            }

            // Step 3: Wait for boot
            logger?.i("Step 3: Waiting for container boot (max 15s)...")
            var wait = 0
            while (wait < 15) {
                Thread.sleep(1000)
                wait++
                val (isRunning, _) = ContainerManager.checkContainerStatusPublic(containerName)
                if (isRunning) {
                    logger?.i("Container running after ${wait}s")
                    break
                }
            }

            val (isRunning, _) = ContainerManager.checkContainerStatusPublic(containerName)
            if (!isRunning) {
                logger?.w("Container may still be booting...")
            }

            // Step 4: Open Termux:X11
            logger?.i("Step 4: Opening Termux:X11...")
            openTermuxX11()

            logger?.i("=== X11 Session started! ===")
        } catch (e: Exception) {
            logger?.e("Error: ${e.message}")
        }
    }

    suspend fun stopAll(logger: ContainerLogger? = null) = withContext(Dispatchers.IO) {
        try {
            logger?.i("Stopping everything...")
            stopLoader(logger)
            val containers = ContainerManager.listContainers()
            for (c in containers) {
                if (c.isRunning) {
                    ContainerManager.stopContainer(c.name, logger)
                    logger?.i("Container ${c.name} stopped")
                }
            }
            logger?.i("All stopped")
        } catch (e: Exception) {
            logger?.e("Error: ${e.message}")
        }
    }
}
