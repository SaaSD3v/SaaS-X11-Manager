package com.saas.x11manager.util

import android.util.Log
import com.saas.x11manager.X11Application
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class VncRuntimeLease(
    val restoreStoppedOnFailure: Boolean
)

data class VncStartResult(
    val success: Boolean,
    val port: Int,
    val displayName: String? = null,
    val mirroredDisplayName: String? = null
)

object VncServerManager {
    private const val STATE_DIR = "/run/saas-x11-manager-vnc"
    private const val PASSWORD_FILE = "/root/.vnc/passwd"
    private const val SESSION_SCRIPT = "/usr/local/bin/saas-vnc-session"
    private const val SERVER_LOG = "/root/.vnc/saas-vnc-server.log"
    private const val SESSION_LOG = "/root/.vnc/saas-vnc-session.log"

    suspend fun startStandalone(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        port: Int,
        password: String? = null,
        logger: ContainerLogger? = null,
        beforeGraphicSession: (suspend () -> Unit)? = null
    ): VncStartResult = withContext(Dispatchers.IO) {
        logger?.i("--- Starting External TigerVNC Session ---")
        logger?.i("")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Graphic session: ${session.label}")
        logger?.i("[CTX] VNC port: $port")
        logger?.i("[CTX] Mode: standalone virtual display")
        if (!VncSettings.isValidPort(port)) {
            logger?.e("[-] Invalid VNC port: $port")
            return@withContext VncStartResult(false, port)
        }
        val launchSettings = VncSettings.getLaunchSettings(X11Application.instance, containerName)
        val settingsError = VncSettings.validateLaunchSettings(launchSettings)
        if (settingsError != null) {
            logger?.e("[-] Invalid TigerVNC settings: $settingsError")
            return@withContext VncStartResult(false, port)
        }
        logger?.i("[CTX] VNC resolution: ${launchSettings.geometry}")
        logger?.i("[CTX] VNC depth: ${launchSettings.depth}")
        val lease = ensureContainerReady(containerName, logger)
            ?: return@withContext VncStartResult(false, port)
        logger?.i(LogLayout.SPACER)
        logger?.i("[VNC] Preparing TigerVNC runtime")
        var success = false
        try {
            if (!prepareTigerVnc(containerName, platform, session, false, password, logger)) {
                return@withContext VncStartResult(false, port)
            }
            logger?.i(LogLayout.SPACER)
            logger?.i("[VNC] Launching standalone VNC runtime")
            if (!stopManagedVnc(containerName, logger)) {
                logger?.e("[-] Refusing to replace an unverified VNC PID lease")
                return@withContext VncStartResult(false, port)
            }
            if (isPortListening(containerName, port)) {
                logger?.e("[-] Port $port is already in use inside the container network namespace")
                logger?.e("[-] Choose another VNC port in General settings")
                return@withContext VncStartResult(false, port)
            }
            if (!stopIntegratedGraphicService(containerName, logger)) {
                logger?.e("[-] Integrated X11 startup service is still active; standalone VNC was not started")
                return@withContext VncStartResult(false, port)
            }
            val displayNumber = findFreeVirtualDisplay(containerName)
            if (displayNumber == null) {
                logger?.e("[-] No free VNC X display was found in :1..:20")
                return@withContext VncStartResult(false, port)
            }
            val displayName = ":$displayNumber"
            logger?.i("[CTX] VNC X display: $displayName")
            val launchCommand = standaloneLaunchCommand(displayNumber, port, launchSettings, password != null)
            if (!runContainerCommand(containerName, "Launching TigerVNC virtual X server", launchCommand, logger)) {
                logContainerFileTail(containerName, SERVER_LOG, logger)
                return@withContext VncStartResult(false, port, displayName)
            }
            if (!waitForPort(containerName, port)) {
                logger?.e("[-] TigerVNC did not begin listening on TCP $port")
                logContainerFileTail(containerName, SERVER_LOG, logger)
                stopManagedVnc(containerName, logger)
                return@withContext VncStartResult(false, port, displayName)
            }
            beforeGraphicSession?.invoke()
            val sessionLaunch =
                "DISPLAY=${shellQuote(displayName)} nohup $SESSION_SCRIPT >$SESSION_LOG 2>&1 & " +
                    "session_pid=\$!; " + VncRuntimeSafety.recordLease(STATE_DIR, "session", "session_pid")
            if (!runContainerCommand(containerName, "Launching ${session.label} on VNC $displayName", sessionLaunch, logger)) {
                logContainerFileTail(containerName, SESSION_LOG, logger)
                stopManagedVnc(containerName, logger)
                return@withContext VncStartResult(false, port, displayName)
            }
            delay(750)
            if (!isPortListening(containerName, port)) {
                logger?.e("[-] TigerVNC stopped after the graphical session launch")
                logContainerFileTail(containerName, SERVER_LOG, logger)
                logContainerFileTail(containerName, SESSION_LOG, logger)
                stopManagedVnc(containerName, logger)
                return@withContext VncStartResult(false, port, displayName)
            }
            logger?.i("[+] VNC server started successfully")
            success = true
            VncStartResult(true, port, displayName = displayName)
        } finally {
            if (!success && lease.restoreStoppedOnFailure) restoreStoppedState(containerName, logger)
        }
    }

    suspend fun startMirror(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        integratedDisplayName: String,
        port: Int,
        password: String? = null,
        logger: ContainerLogger? = null
    ): VncStartResult = withContext(Dispatchers.IO) {
        logger?.i("--- Starting TigerVNC Mirror ---")
        logger?.i("")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Graphic session: ${session.label}")
        logger?.i("[CTX] Integrated display: $integratedDisplayName")
        logger?.i("[CTX] VNC port: $port")
        logger?.i("[CTX] Mode: mirror existing Integrated X11 screen")
        if (!VncSettings.isValidPort(port)) return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
        val launchSettings = VncSettings.getLaunchSettings(X11Application.instance, containerName)
        VncSettings.validateLaunchSettings(launchSettings)?.let {
            logger?.e("[-] Invalid TigerVNC settings: $it")
            return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
        }
        val lease = ensureContainerReady(containerName, logger)
            ?: return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
        var success = false
        try {
            if (!prepareTigerVnc(containerName, platform, session, true, password, logger)) {
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }
            if (!stopManagedVnc(containerName, logger)) {
                logger?.e("[-] Refusing to replace an unverified VNC PID lease")
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }
            if (isPortListening(containerName, port)) return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            val displaySocket = "/tmp/.X11-unix/X${integratedDisplayName.removePrefix(":")}"
            if (!probeContainer(containerName, "test -S ${shellQuote(displaySocket)}")) {
                logger?.e("[-] Integrated display socket is not visible in the container: $displaySocket")
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }
            val launchCommand = mirrorLaunchCommand(integratedDisplayName, port, launchSettings)
            if (!runContainerCommand(containerName, "Publishing Integrated X11 through x0vncserver", launchCommand, logger)) {
                logContainerFileTail(containerName, SERVER_LOG, logger)
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }
            if (!waitForPort(containerName, port)) {
                logger?.e("[-] x0vncserver did not begin listening on TCP $port")
                logContainerFileTail(containerName, SERVER_LOG, logger)
                stopManagedVnc(containerName, logger)
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }
            logConnectionAddresses(containerName, port, integratedDisplayName, true, logger)
            logger?.i("[+] VNC mirror started successfully")
            success = true
            VncStartResult(true, port, mirroredDisplayName = integratedDisplayName)
        } finally {
            if (!success && lease.restoreStoppedOnFailure) restoreStoppedState(containerName, logger)
        }
    }

    suspend fun stopManagedVnc(containerName: String, logger: ContainerLogger? = null): Boolean = withContext(Dispatchers.IO) {
        val result = runContainerCommandRaw(containerName, VncRuntimeSafety.stopOwnedRuntime(STATE_DIR))
        if (result) logger?.i("[+] Previous Manager-owned VNC runtime cleared")
        else logger?.w("[!] VNC PID state did not match the recorded Manager lease; no unverified PID was killed")
        result
    }

    private suspend fun prepareTigerVnc(
        containerName: String,
        platform: ContainerPlatform?,
        session: GraphicSession,
        needsMirror: Boolean,
        password: String?,
        logger: ContainerLogger?
    ): Boolean {
        if (password != null && !VncSettings.isValidPassword(password)) return false
        if (needsMirror && password == null && !probeContainer(containerName, "test -s $PASSWORD_FILE")) return false
        val requiredServerPresent = if (needsMirror) probeContainer(containerName, "command -v x0vncserver >/dev/null 2>&1")
            else probeContainer(containerName, "command -v Xtigervnc >/dev/null 2>&1 || command -v Xvnc >/dev/null 2>&1")
        val passwordToolPresent = password == null || probeContainer(containerName, "command -v tigervncpasswd >/dev/null 2>&1 || command -v vncpasswd >/dev/null 2>&1")
        if (!(requiredServerPresent && passwordToolPresent)) {
            val resolvedPlatform = platform ?: detectPlatform(containerName) ?: return false
            if (!installTigerVnc(containerName, resolvedPlatform, needsMirror, logger)) return false
        }
        if (password != null) {
            val passwordCommand = "mkdir -p /root/.vnc && chmod 700 /root/.vnc && tool=\$(command -v tigervncpasswd 2>/dev/null || command -v vncpasswd 2>/dev/null); [ -n \"\$tool\" ] || exit 1; printf '%s\\n' ${shellQuote(password)} | \"\$tool\" -f > $PASSWORD_FILE && chmod 600 $PASSWORD_FILE && test -s $PASSWORD_FILE"
            if (!runContainerCommandRaw(containerName, passwordCommand)) return false
        } else if (!needsMirror && !runContainerCommandRaw(containerName, "rm -f $PASSWORD_FILE")) return false
        if (!needsMirror) {
            val launcher = sessionLauncher(session)
            val writeLauncher = "mkdir -p /usr/local/bin /root/.vnc && printf '%s' ${shellQuote(launcher)} > $SESSION_SCRIPT && chmod 755 $SESSION_SCRIPT"
            if (!runContainerCommand(containerName, "Writing user-aware VNC session launcher for ${session.label}", writeLauncher, logger, false)) return false
        }
        return true
    }

    private suspend fun installTigerVnc(containerName: String, platform: ContainerPlatform, needsMirror: Boolean, logger: ContainerLogger?): Boolean {
        val command = when (platform) {
            ContainerPlatform.ALPINE -> "apk update && apk add tigervnc"
            ContainerPlatform.UBUNTU -> {
                val packages = if (needsMirror) "tigervnc-scraping-server tigervnc-tools" else "tigervnc-standalone-server tigervnc-tools"
                "DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $packages"
            }
        }
        return runContainerCommand(containerName, "Installing TigerVNC (${platform.label})", command, logger)
    }

    private suspend fun ensureContainerReady(containerName: String, logger: ContainerLogger?): VncRuntimeLease? {
        val (initialStatus, initialPid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        val restoreOnFailure = initialStatus == ContainerStatus.STOPPED
        when (initialStatus) {
            ContainerStatus.RUNNING -> logger?.i("[+] Container already running${if (initialPid != null) " (PID=$initialPid)" else ""}")
            ContainerStatus.STOPPED, ContainerStatus.UNKNOWN -> ContainerManager.startContainer(containerName, logger)
        }
        val deadline = System.nanoTime() + 15_000_000_000L
        while (true) {
            if (probeContainer(containerName, "printf '%s\\n' __SAAS_VNC_READY__")) return VncRuntimeLease(restoreOnFailure)
            if (System.nanoTime() >= deadline) {
                if (restoreOnFailure) restoreStoppedState(containerName, logger)
                return null
            }
            delay(500)
        }
    }

    private suspend fun restoreStoppedState(containerName: String, logger: ContainerLogger?) {
        ContainerManager.stopContainer(containerName, logger)
    }

    private suspend fun stopIntegratedGraphicService(containerName: String, logger: ContainerLogger?): Boolean {
        val stopped = runContainerCommandRaw(containerName, VncRuntimeSafety.stopIntegratedGraphicService())
        if (stopped) logger?.i("[+] Standalone VNC isolated from the Integrated X11 startup service")
        else logger?.w("[!] Integrated X11 startup service stop could not be confirmed")
        return stopped
    }

    private fun sessionLauncher(session: GraphicSession): String = GraphicSessionInitFiles.vncSessionScript(session, "/bin/sh")

    private fun standaloneLaunchCommand(displayNumber: Int, port: Int, settings: VncLaunchSettings, passwordEnabled: Boolean): String {
        val argv = TigerVncCommandOptions.standalone(settings, displayNumber, port, PASSWORD_FILE.takeIf { passwordEnabled }).joinToString(" ") { shellQuote(it) }
        return "mkdir -p /root/.vnc /tmp/.X11-unix $STATE_DIR && chmod 1777 /tmp/.X11-unix && server=\$(command -v Xtigervnc 2>/dev/null || command -v Xvnc 2>/dev/null); [ -n \"\$server\" ] || exit 1; rm -f /tmp/.X${displayNumber}-lock; nohup \"\$server\" $argv >$SERVER_LOG 2>&1 & server_pid=\$!; " + VncRuntimeSafety.recordLease(STATE_DIR, "server", "server_pid") + "; printf '%s\\n' standalone > $STATE_DIR/mode; printf '%s\\n' $port > $STATE_DIR/port; printf '%s\\n' $displayNumber > $STATE_DIR/display"
    }

    private fun mirrorLaunchCommand(displayName: String, port: Int, settings: VncLaunchSettings): String {
        val argv = TigerVncCommandOptions.mirror(settings, displayName, port, PASSWORD_FILE).joinToString(" ") { shellQuote(it) }
        return "mkdir -p /root/.vnc $STATE_DIR && server=\$(command -v x0vncserver 2>/dev/null); [ -n \"\$server\" ] || exit 1; nohup \"\$server\" $argv >$SERVER_LOG 2>&1 & server_pid=\$!; " + VncRuntimeSafety.recordLease(STATE_DIR, "server", "server_pid") + "; printf '%s\\n' mirror > $STATE_DIR/mode; printf '%s\\n' $port > $STATE_DIR/port; printf '%s\\n' ${shellQuote(displayName)} > $STATE_DIR/display"
    }

    private suspend fun findFreeVirtualDisplay(containerName: String): Int? {
        val command = "n=1; while [ \"\$n\" -le 20 ]; do if [ ! -S /tmp/.X11-unix/X\$n ] && [ ! -e /tmp/.X\$n-lock ]; then printf '%s\\n' \"\$n\"; exit 0; fi; n=\$((n+1)); done; exit 1"
        return runContainerCapture(containerName, command).firstOrNull()?.trim()?.toIntOrNull()
    }

    private suspend fun waitForPort(containerName: String, port: Int, timeoutMillis: Long = 10_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isPortListening(containerName, port)) return true
            if (System.nanoTime() >= deadline) return false
            delay(250)
        }
    }

    private fun isPortListening(containerName: String, port: Int): Boolean = probeContainer(containerName, portListeningCommand(port))
    internal fun portListeningCommand(port: Int): String = VncRuntimeSafety.listeningPort(port)

    private suspend fun detectPlatform(containerName: String): ContainerPlatform? = when {
        probeContainer(containerName, "command -v apk >/dev/null 2>&1") -> ContainerPlatform.ALPINE
        probeContainer(containerName, "command -v apt-get >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1") -> ContainerPlatform.UBUNTU
        else -> null
    }

    private suspend fun logConnectionAddresses(containerName: String, port: Int, displayName: String, mirror: Boolean, logger: ContainerLogger?) {
        logger?.i("")
        logger?.i("--- VNC Connection ---")
        logger?.i("[CTX] TCP port: $port")
        if (mirror) logger?.i("[CTX] Shared Integrated X11 display: $displayName") else logger?.i("[CTX] VNC X display: $displayName")
        val containerIps = runContainerCapture(containerName, "hostname -I 2>/dev/null || ip -4 -o addr show scope global 2>/dev/null | sed -n 's/.* inet \\([0-9.]*\\)\\/.*/\\1/p'").flatMap { it.trim().split(Regex("\\s+")) }.filter(::isIpv4Address).distinct()
        containerIps.forEach { logger?.i("[+] Container: $it:$port") }
        val hostIps = androidHostIpv4Addresses()
        val netMode = ContainerManager.getContainerInfo(containerName)?.netMode?.lowercase()
        hostIps.forEach { logger?.i("[+] Android/LAN: $it:$port") }
        if (hostIps.isNotEmpty() && netMode != "host") logger?.w("[!] Container network mode is ${netMode ?: "unknown"}; Android/LAN addresses may require DroidSpaces NAT/port forwarding")
        logger?.i("[+] Connect with any standard VNC client using one of the reachable addresses above")
    }

    private fun androidHostIpv4Addresses(): List<String> = try {
        Shell.cmd("ip -4 -o addr show scope global 2>/dev/null").exec().out.mapNotNull { Regex("\\binet\\s+([0-9.]+)/").find(it)?.groupValues?.getOrNull(1) }.filter(::isIpv4Address).distinct()
    } catch (_: Exception) { emptyList() }

    private fun isIpv4Address(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } && value != "127.0.0.1"
    }

    private suspend fun logContainerFileTail(containerName: String, path: String, logger: ContainerLogger?) {
        runContainerCapture(containerName, "tail -n 30 ${shellQuote(path)} 2>/dev/null").forEach { logger?.w(it) }
    }

    private fun probeContainer(containerName: String, command: String): Boolean = try {
        Shell.cmd(containerHostCommand(containerName, command) + " 2>/dev/null").exec().isSuccess
    } catch (_: Exception) { false }

    private fun runContainerCapture(containerName: String, command: String): List<String> = try {
        val result = Shell.cmd(containerHostCommand(containerName, command) + " 2>/dev/null").exec()
        if (result.isSuccess) result.out else emptyList()
    } catch (_: Exception) { emptyList() }

    private suspend fun runContainerCommand(containerName: String, title: String, command: String, logger: ContainerLogger?, logCommand: Boolean = true): Boolean {
        logger?.i("[+] $title")
        if (logCommand) logger?.i("root@$containerName: $command")
        val hostCommand = containerHostCommand(containerName, command)
        val result = if (logger == null) Shell.cmd(hostCommand).exec() else {
            val stdout = object : CallbackList<String>() { override fun onAddElement(line: String) { logger.logImmediate(Log.INFO, line) } }
            val stderr = object : CallbackList<String>() { override fun onAddElement(line: String) { logger.logImmediate(Log.WARN, line) } }
            Shell.cmd(hostCommand).to(stdout, stderr).exec()
        }
        if (!result.isSuccess) {
            logger?.e("[-] FAIL (exit ${result.code})")
            return false
        }
        return true
    }

    private fun runContainerCommandRaw(containerName: String, command: String): Boolean = try {
        Shell.cmd(containerHostCommand(containerName, command)).exec().isSuccess
    } catch (_: Exception) { false }

    private fun containerHostCommand(containerName: String, command: String): String =
        "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(command)}"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
