package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LoaderStatus { Running, Stopped }

object X11SessionManager {

    suspend fun getLoaderStatus(): LoaderStatus = withContext(Dispatchers.IO) {
        try {
            val sockCheck = Shell.cmd("test -S '${Constants.X11_SOCK_FILE}' && echo ok").exec()
            if (sockCheck.isSuccess && sockCheck.out.isNotEmpty() && sockCheck.out[0].contains("ok")) {
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
            val result = Shell.cmd("pidof termux-x11 2>/dev/null").exec()
            result.out.firstOrNull()?.trim()?.toIntOrNull()
        } catch (_: Exception) { null }
    }

    suspend fun startPulseAudio(logger: ContainerLogger? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            logger?.i("[*] Preparing PulseAudio...")

            Shell.cmd("killall pulseaudio 2>/dev/null").exec()
            Shell.cmd("rm -f '${Constants.PULSE_SOCK}'").exec()
            Shell.cmd("rm -rf /data/data/com.termux/files/usr/tmp/pulse-* 2>/dev/null").exec()

            logger?.i("[*] Starting PulseAudio daemon...")
            Shell.cmd(
                "run-as ${Constants.TERMUX_PACKAGE_NAME} " +
                "${Constants.PULSE_BIN} " +
                "--load 'module-native-protocol-unix socket=${Constants.PULSE_SOCK} auth-anonymous=1' " +
                "--exit-idle-time=-1 " +
                "--daemonize=yes " +
                "--use-pid-file=false " +
                "--disallow-exit"
            ).exec()

            logger?.i("[*] Waiting for socket (5s timeout)...")
            var wait = 0
            while (wait < 5) {
                Thread.sleep(1000)
                wait++
                val sockCheck = Shell.cmd("test -S '${Constants.PULSE_SOCK}' && echo ok").exec()
                if (sockCheck.isSuccess && sockCheck.out.isNotEmpty() && sockCheck.out[0].contains("ok")) {
                    break
                }
            }

            val sockExists = Shell.cmd("test -S '${Constants.PULSE_SOCK}' && echo ok").exec()
            if (sockExists.isSuccess && sockExists.out.isNotEmpty() && sockExists.out[0].contains("ok")) {
                val pid = getPulseAudioPid() ?: 0
                logger?.i("[+] PulseAudio active (PID=$pid)")
                Result.success(pid)
            } else {
                logger?.e("[-] PulseAudio socket not created")
                Result.failure(Exception("PulseAudio socket not created"))
            }
        } catch (e: Exception) {
            logger?.e("[-] PulseAudio error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun stopPulseAudio(logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val pid = getPulseAudioPid()
            if (pid != null && pid > 0) {
                Shell.cmd("kill -9 $pid 2>/dev/null").exec()
                logger?.i("[+] Stopped PulseAudio (PID=$pid)")
            }
            Shell.cmd("rm -f '${Constants.PULSE_SOCK}'").exec()
            Shell.cmd("rm -rf /data/data/com.termux/files/usr/tmp/pulse-* 2>/dev/null").exec()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getPulseAudioPid(): Int? = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("pidof pulseaudio 2>/dev/null").exec()
            result.out.firstOrNull()?.trim()?.toIntOrNull()
        } catch (_: Exception) { null }
    }

    suspend fun startLoader(logger: ContainerLogger? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            logger?.i("[*] Preparing X11 environment...")

            val stalePid = Shell.cmd("pidof termux-x11 2>/dev/null").exec()
            if (stalePid.isSuccess && stalePid.out.isNotEmpty()) {
                for (pid in stalePid.out) {
                    val trimmed = pid.trim()
                    if (trimmed.isNotEmpty()) {
                        Shell.cmd("kill -9 $trimmed 2>/dev/null").exec()
                        logger?.i("[+] Killed stale process (PID=$trimmed)")
                    }
                }
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

            logger?.i("[*] Waiting for socket (10s timeout)...")
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
            val pid = getLoaderPid()
            if (pid != null && pid > 0) {
                Shell.cmd("kill -9 $pid 2>/dev/null").exec()
                logger?.i("[+] Stopped loader (PID=$pid)")
            }
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/X* 2>/dev/null").exec()
            Shell.cmd("rm -f '${Constants.X11_SOCK_DIR}'/*-lock 2>/dev/null").exec()
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
        enablePulseAudioFix: Boolean = false,
        logger: ContainerLogger? = null
    ) = withContext(Dispatchers.IO) {
        try {
            logger?.i("--- Starting X11 Session ---")
            logger?.i("")

            if (enablePulseAudioFix) {
                val paResult = startPulseAudio(logger)
                paResult.onFailure { e ->
                    logger?.w("[!] PulseAudio failed: ${e.message}")
                    logger?.i("[*] Continuing without audio...")
                }
                logger?.i("")
            }

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

            logger?.i("[*] Waiting for boot (15s timeout)...")
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
            if (!isRunning) {
                logger?.w("[!] Container may still be booting")
            }

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
            stopPulseAudio(logger)
            stopLoader(logger)
            val containers = ContainerManager.listContainers()
            for (c in containers) {
                if (c.isRunning) {
                    ContainerManager.stopContainer(c.name, logger)
                }
            }
            logger?.i("[+] All stopped")
        } catch (e: Exception) {
            logger?.e("[-] Error: ${e.message}")
        }
    }
}
