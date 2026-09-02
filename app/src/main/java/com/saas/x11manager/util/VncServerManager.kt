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

/**
 * External TigerVNC integration.
 *
 * No VNC implementation is bundled in the APK. The Manager detects TigerVNC in
 * the selected DroidSpaces container and installs the distro package only when
 * the required executable is missing. Standalone VNC owns a private Xvnc display;
 * BOTH uses x0vncserver to expose the exact already-running Integrated X11 screen
 * instead of starting a second desktop session.
 */
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
        logger: ContainerLogger? = null
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

        val launchSettings = VncSettings.getLaunchSettings(
            X11Application.instance,
            containerName
        )
        val settingsError = VncSettings.validateLaunchSettings(launchSettings)
        if (settingsError != null) {
            logger?.e("[-] Invalid TigerVNC settings: $settingsError")
            return@withContext VncStartResult(false, port)
        }
        logger?.i("[CTX] VNC resolution: ${launchSettings.geometry}")
        logger?.i("[CTX] VNC depth: ${launchSettings.depth}")

        val lease = ensureContainerReady(containerName, logger)
            ?: return@withContext VncStartResult(false, port)

        var success = false
        try {
            if (!prepareTigerVnc(
                    containerName = containerName,
                    platform = platform,
                    session = session,
                    needsMirror = false,
                    password = password,
                    logger = logger
                )) {
                return@withContext VncStartResult(false, port)
            }

            stopManagedVnc(containerName, logger)
            if (isPortListening(containerName, port)) {
                logger?.e("[-] Port $port is already in use inside the container network namespace")
                logger?.e("[-] Choose another VNC port in General settings")
                return@withContext VncStartResult(false, port)
            }

            stopIntegratedGraphicService(containerName, logger)

            val displayNumber = findFreeVirtualDisplay(containerName)
            if (displayNumber == null) {
                logger?.e("[-] No free VNC X display was found in :1..:20")
                return@withContext VncStartResult(false, port)
            }
            val displayName = ":$displayNumber"
            logger?.i("[CTX] VNC X display: $displayName")

            val launchCommand = standaloneLaunchCommand(
                displayNumber = displayNumber,
                port = port,
                settings = launchSettings
            )
            if (!runContainerCommand(
                    containerName,
                    "Launching TigerVNC virtual X server",
                    launchCommand,
                    logger
                )) {
                logContainerFileTail(containerName, SERVER_LOG, logger)
                return@withContext VncStartResult(false, port, displayName)
            }

            if (!waitForPort(containerName, port)) {
                logger?.e("[-] TigerVNC did not begin listening on TCP $port")
                logContainerFileTail(containerName, SERVER_LOG, logger)
                stopManagedVnc(containerName, logger)
                return@withContext VncStartResult(false, port, displayName)
            }

            val sessionLaunch =
                "DISPLAY=${shellQuote(displayName)} " +
                    "nohup $SESSION_SCRIPT >$SESSION_LOG 2>&1 & " +
                    "session_pid=\$!; printf '%s\\n' \"\$session_pid\" > $STATE_DIR/session.pid"
            if (!runContainerCommand(
                    containerName,
                    "Launching ${session.label} on VNC $displayName",
                    sessionLaunch,
                    logger
                )) {
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

            logConnectionAddresses(containerName, port, displayName, mirror = false, logger = logger)
            logger?.i("[+] VNC server started successfully")
            success = true
            VncStartResult(true, port, displayName = displayName)
        } finally {
            if (!success && lease.restoreStoppedOnFailure) {
                restoreStoppedState(containerName, logger)
            }
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

        if (!VncSettings.isValidPort(port)) {
            logger?.e("[-] Invalid VNC port: $port")
            return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
        }

        val launchSettings = VncSettings.getLaunchSettings(
            X11Application.instance,
            containerName
        )
        val settingsError = VncSettings.validateLaunchSettings(launchSettings)
        if (settingsError != null) {
            logger?.e("[-] Invalid TigerVNC settings: $settingsError")
            return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
        }
        if (launchSettings.mirrorGeometry.isNotBlank()) {
            logger?.i("[CTX] VNC mirror crop: ${launchSettings.mirrorGeometry}")
        }

        val lease = ensureContainerReady(containerName, logger)
            ?: return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)

        var success = false
        try {
            if (!prepareTigerVnc(
                    containerName = containerName,
                    platform = platform,
                    session = session,
                    needsMirror = true,
                    password = password,
                    logger = logger
                )) {
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }

            stopManagedVnc(containerName, logger)
            if (isPortListening(containerName, port)) {
                logger?.e("[-] Port $port is already in use inside the container network namespace")
                logger?.e("[-] Choose another VNC port in General settings")
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }

            val displaySocket = "/tmp/.X11-unix/X${integratedDisplayName.removePrefix(":")}"
            if (!probeContainer(containerName, "test -S ${shellQuote(displaySocket)}")) {
                logger?.e("[-] Integrated display socket is not visible in the container: $displaySocket")
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }

            val launchCommand = mirrorLaunchCommand(
                displayName = integratedDisplayName,
                port = port,
                settings = launchSettings
            )
            if (!runContainerCommand(
                    containerName,
                    "Publishing Integrated X11 through x0vncserver",
                    launchCommand,
                    logger
                )) {
                logContainerFileTail(containerName, SERVER_LOG, logger)
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }

            if (!waitForPort(containerName, port)) {
                logger?.e("[-] x0vncserver did not begin listening on TCP $port")
                logContainerFileTail(containerName, SERVER_LOG, logger)
                stopManagedVnc(containerName, logger)
                return@withContext VncStartResult(false, port, mirroredDisplayName = integratedDisplayName)
            }

            logConnectionAddresses(
                containerName,
                port,
                integratedDisplayName,
                mirror = true,
                logger = logger
            )
            logger?.i("[+] VNC mirror started successfully")
            success = true
            VncStartResult(true, port, mirroredDisplayName = integratedDisplayName)
        } finally {
            if (!success && lease.restoreStoppedOnFailure) {
                restoreStoppedState(containerName, logger)
            }
        }
    }

    suspend fun stopManagedVnc(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val command =
            "for pidfile in $STATE_DIR/session.pid $STATE_DIR/server.pid; do " +
                "[ -f \"\$pidfile\" ] || continue; " +
                "pid=\$(cat \"\$pidfile\" 2>/dev/null); " +
                "case \"\$pid\" in ''|*[!0-9]*) continue ;; esac; " +
                "if kill -0 \"\$pid\" 2>/dev/null; then kill \"\$pid\" 2>/dev/null || true; fi; " +
                "done; " +
                "sleep 1; " +
                "for pidfile in $STATE_DIR/session.pid $STATE_DIR/server.pid; do " +
                "[ -f \"\$pidfile\" ] || continue; " +
                "pid=\$(cat \"\$pidfile\" 2>/dev/null); " +
                "case \"\$pid\" in ''|*[!0-9]*) continue ;; esac; " +
                "if kill -0 \"\$pid\" 2>/dev/null; then kill -9 \"\$pid\" 2>/dev/null || true; fi; " +
                "done; rm -rf $STATE_DIR"
        val result = runContainerCommandRaw(containerName, command)
        if (result) logger?.i("[+] Previous Manager-owned VNC runtime cleared")
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
        if (password != null && !VncSettings.isValidPassword(password)) {
            logger?.e(
                "[-] VNC password must contain ${VncSettings.MIN_PASSWORD_LENGTH}-${VncSettings.MAX_PASSWORD_LENGTH} characters"
            )
            return false
        }

        val existingPassword = probeContainer(containerName, "test -s $PASSWORD_FILE")
        if (password == null && !existingPassword) {
            logger?.e("[-] No TigerVNC password is configured for this container")
            logger?.e("[-] Open Edit container and choose VNC/Both once to configure it")
            return false
        }

        val requiredServerPresent = if (needsMirror) {
            probeContainer(containerName, "command -v x0vncserver >/dev/null 2>&1")
        } else {
            probeContainer(
                containerName,
                "command -v Xtigervnc >/dev/null 2>&1 || command -v Xvnc >/dev/null 2>&1"
            )
        }
        val passwordToolPresent = password == null || probeContainer(
            containerName,
            "command -v tigervncpasswd >/dev/null 2>&1 || command -v vncpasswd >/dev/null 2>&1"
        )

        if (requiredServerPresent && passwordToolPresent) {
            logger?.i("[+] TigerVNC already installed; package manager skipped")
        } else {
            val resolvedPlatform = platform ?: detectPlatform(containerName)
            if (resolvedPlatform == null) {
                logger?.e("[-] Could not detect apk or apt/dpkg for TigerVNC installation")
                return false
            }
            logger?.i("[*] TigerVNC component missing; installing only what is required...")
            if (!installTigerVnc(containerName, resolvedPlatform, needsMirror, logger)) return false

            val verified = if (needsMirror) {
                probeContainer(containerName, "command -v x0vncserver >/dev/null 2>&1")
            } else {
                probeContainer(
                    containerName,
                    "command -v Xtigervnc >/dev/null 2>&1 || command -v Xvnc >/dev/null 2>&1"
                )
            }
            if (!verified) {
                logger?.e("[-] TigerVNC package installation finished without the required server binary")
                return false
            }
        }

        if (password != null) {
            val passwordCommand =
                "mkdir -p /root/.vnc && chmod 700 /root/.vnc && " +
                    "tool=\$(command -v tigervncpasswd 2>/dev/null || command -v vncpasswd 2>/dev/null); " +
                    "[ -n \"\$tool\" ] || exit 1; " +
                    "printf '%s\\n' ${shellQuote(password)} | \"\$tool\" -f > $PASSWORD_FILE && " +
                    "chmod 600 $PASSWORD_FILE && test -s $PASSWORD_FILE"
            logger?.i("[+] Configuring TigerVNC authentication")
            // Deliberately do not print this command: it contains the user password.
            if (!runContainerCommandRaw(containerName, passwordCommand)) {
                logger?.e("[-] Could not create the TigerVNC password file")
                return false
            }
            logger?.i("[+] TigerVNC password file ready")
        }

        if (!needsMirror) {
            val launcher = sessionLauncher(session)
            val writeLauncher =
                "mkdir -p /usr/local/bin /root/.vnc && " +
                    "printf '%s' ${shellQuote(launcher)} > $SESSION_SCRIPT && " +
                    "chmod 755 $SESSION_SCRIPT"
            if (!runContainerCommand(
                    containerName,
                    "Writing VNC session launcher for ${session.label}",
                    writeLauncher,
                    logger,
                    logCommand = false
                )) return false
        }

        return true
    }

    private suspend fun installTigerVnc(
        containerName: String,
        platform: ContainerPlatform,
        needsMirror: Boolean,
        logger: ContainerLogger?
    ): Boolean {
        val command = when (platform) {
            ContainerPlatform.ALPINE ->
                "apk update && apk add tigervnc"

            ContainerPlatform.UBUNTU -> {
                val packages = if (needsMirror) {
                    "tigervnc-scraping-server tigervnc-tools"
                } else {
                    "tigervnc-standalone-server tigervnc-tools"
                }
                "DEBIAN_FRONTEND=noninteractive apt-get update && " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $packages"
            }
        }
        return runContainerCommand(
            containerName,
            "Installing TigerVNC (${platform.label})",
            command,
            logger
        )
    }

    private suspend fun ensureContainerReady(
        containerName: String,
        logger: ContainerLogger?
    ): VncRuntimeLease? {
        val (initialStatus, initialPid) = ContainerManager.getContainerRuntimeStatePublic(containerName)
        val restoreOnFailure = initialStatus == ContainerStatus.STOPPED
        when (initialStatus) {
            ContainerStatus.RUNNING -> logger?.i(
                "[+] Container already running${if (initialPid != null) " (PID=$initialPid)" else ""}"
            )

            ContainerStatus.STOPPED, ContainerStatus.UNKNOWN -> {
                logger?.i("[*] Starting container for VNC...")
                ContainerManager.startContainer(containerName, logger)
            }
        }

        val deadline = System.nanoTime() + 15_000_000_000L
        while (true) {
            if (probeContainer(containerName, "printf '%s\\n' __SAAS_VNC_READY__")) {
                logger?.i("[+] Container command channel ready")
                return VncRuntimeLease(restoreStoppedOnFailure = restoreOnFailure)
            }
            if (System.nanoTime() >= deadline) {
                logger?.e("[-] Container did not become ready for VNC commands")
                if (restoreOnFailure) restoreStoppedState(containerName, logger)
                return null
            }
            delay(500)
        }
    }

    private suspend fun restoreStoppedState(containerName: String, logger: ContainerLogger?) {
        logger?.i("[*] Restoring stopped container state after VNC failure...")
        ContainerManager.stopContainer(containerName, logger)
    }

    private suspend fun stopIntegratedGraphicService(
        containerName: String,
        logger: ContainerLogger?
    ) {
        val command =
            "if command -v systemctl >/dev/null 2>&1; then " +
                "systemctl stop x11-session.service setup-x11-socket.service >/dev/null 2>&1 || true; " +
                "elif command -v rc-service >/dev/null 2>&1; then " +
                "rc-service x11-session stop >/dev/null 2>&1 || true; " +
                "fi"
        if (runContainerCommandRaw(containerName, command)) {
            logger?.i("[+] Standalone VNC isolated from the Integrated X11 startup service")
        }
    }

    private fun sessionLauncher(session: GraphicSession): String {
        val protocol = if (session.protocol == GraphicProtocol.WAYLAND) "wayland" else "x11"
        val waylandEnvironment = if (session.protocol == GraphicProtocol.WAYLAND) {
            "export XDG_SESSION_TYPE=wayland\n" +
                "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
                "mkdir -p \"\$XDG_RUNTIME_DIR\" && chmod 700 \"\$XDG_RUNTIME_DIR\"\n" +
                "unset WAYLAND_DISPLAY\n"
        } else {
            "export XDG_SESSION_TYPE=x11\n" +
                "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
                "mkdir -p \"\$XDG_RUNTIME_DIR\" && chmod 700 \"\$XDG_RUNTIME_DIR\"\n"
        }
        return "#!/bin/sh\n" +
            "export HOME=/root\n" +
            "export USER=root\n" +
            "export SHELL=/bin/sh\n" +
            waylandEnvironment +
            "export SAAS_GRAPHIC_PROTOCOL=$protocol\n" +
            "exec ${session.startCommand}\n"
    }

    private fun standaloneLaunchCommand(
        displayNumber: Int,
        port: Int,
        settings: VncLaunchSettings
    ): String {
        val argv = TigerVncCommandOptions.standalone(
            settings = settings,
            displayNumber = displayNumber,
            port = port,
            passwordFile = PASSWORD_FILE
        ).joinToString(" ") { shellQuote(it) }

        return "mkdir -p /root/.vnc /tmp/.X11-unix $STATE_DIR && chmod 1777 /tmp/.X11-unix && " +
            "server=\$(command -v Xtigervnc 2>/dev/null || command -v Xvnc 2>/dev/null); " +
            "[ -n \"\$server\" ] || exit 1; " +
            "rm -f /tmp/.X${displayNumber}-lock; " +
            "nohup \"\$server\" $argv >$SERVER_LOG 2>&1 & " +
            "server_pid=\$!; printf '%s\\n' \"\$server_pid\" > $STATE_DIR/server.pid; " +
            "printf '%s\\n' standalone > $STATE_DIR/mode; " +
            "printf '%s\\n' $port > $STATE_DIR/port; " +
            "printf '%s\\n' $displayNumber > $STATE_DIR/display"
    }

    private fun mirrorLaunchCommand(
        displayName: String,
        port: Int,
        settings: VncLaunchSettings
    ): String {
        val argv = TigerVncCommandOptions.mirror(
            settings = settings,
            displayName = displayName,
            port = port,
            passwordFile = PASSWORD_FILE
        ).joinToString(" ") { shellQuote(it) }

        return "mkdir -p /root/.vnc $STATE_DIR && " +
            "server=\$(command -v x0vncserver 2>/dev/null); [ -n \"\$server\" ] || exit 1; " +
            "nohup \"\$server\" $argv >$SERVER_LOG 2>&1 & " +
            "server_pid=\$!; printf '%s\\n' \"\$server_pid\" > $STATE_DIR/server.pid; " +
            "printf '%s\\n' mirror > $STATE_DIR/mode; " +
            "printf '%s\\n' $port > $STATE_DIR/port; " +
            "printf '%s\\n' ${shellQuote(displayName)} > $STATE_DIR/display"
    }

    private suspend fun findFreeVirtualDisplay(containerName: String): Int? {
        val command =
            "n=1; while [ \"\$n\" -le 20 ]; do " +
                "if [ ! -S /tmp/.X11-unix/X\$n ] && [ ! -e /tmp/.X\$n-lock ]; then " +
                "printf '%s\\n' \"\$n\"; exit 0; fi; n=\$((n+1)); done; exit 1"
        return runContainerCapture(containerName, command).firstOrNull()?.trim()?.toIntOrNull()
    }

    private suspend fun waitForPort(
        containerName: String,
        port: Int,
        timeoutMillis: Long = 10_000L
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isPortListening(containerName, port)) return true
            if (System.nanoTime() >= deadline) return false
            delay(250)
        }
    }

    private fun isPortListening(containerName: String, port: Int): Boolean =
        probeContainer(containerName, portListeningCommand(port))

    internal fun portListeningCommand(port: Int): String =
        "hex=\$(printf '%04X' $port); " +
            "for table in /proc/net/tcp /proc/net/tcp6; do " +
            "[ -r \"\$table\" ] || continue; " +
            "while read -r sl local rest; do " +
            "case \"\$local\" in *:\$hex) exit 0 ;; esac; " +
            "done < \"\$table\"; done; exit 1"

    private suspend fun detectPlatform(containerName: String): ContainerPlatform? = when {
        probeContainer(containerName, "command -v apk >/dev/null 2>&1") -> ContainerPlatform.ALPINE
        probeContainer(
            containerName,
            "command -v apt-get >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1"
        ) -> ContainerPlatform.UBUNTU
        else -> null
    }

    private suspend fun logConnectionAddresses(
        containerName: String,
        port: Int,
        displayName: String,
        mirror: Boolean,
        logger: ContainerLogger?
    ) {
        logger?.i("")
        logger?.i("--- VNC Connection ---")
        logger?.i("[CTX] TCP port: $port")
        if (mirror) {
            logger?.i("[CTX] Shared Integrated X11 display: $displayName")
        } else {
            logger?.i("[CTX] VNC X display: $displayName")
        }

        val containerIps = runContainerCapture(
            containerName,
            "hostname -I 2>/dev/null || ip -4 -o addr show scope global 2>/dev/null | sed -n 's/.* inet \\([0-9.]*\\)\\/.*/\\1/p'"
        ).flatMap { it.trim().split(Regex("\\s+")) }
            .filter(::isIpv4Address)
            .distinct()

        if (containerIps.isEmpty()) {
            logger?.w("[!] Container IPv4 address could not be resolved")
        } else {
            containerIps.forEach { ip -> logger?.i("[+] Container: $ip:$port") }
        }

        val hostIps = androidHostIpv4Addresses()
        val netMode = ContainerManager.getContainerInfo(containerName)?.netMode?.lowercase()
        hostIps.forEach { ip ->
            logger?.i("[+] Android/LAN: $ip:$port")
        }
        if (hostIps.isNotEmpty() && netMode != "host") {
            logger?.w(
                "[!] Container network mode is ${netMode ?: "unknown"}; Android/LAN addresses may require DroidSpaces NAT/port forwarding"
            )
        }
        logger?.i("[+] Connect with any standard VNC client using one of the reachable addresses above")
    }

    private fun androidHostIpv4Addresses(): List<String> {
        return try {
            val result = Shell.cmd("ip -4 -o addr show scope global 2>/dev/null").exec()
            result.out.mapNotNull { line ->
                Regex("\\binet\\s+([0-9.]+)/").find(line)?.groupValues?.getOrNull(1)
            }.filter(::isIpv4Address).distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isIpv4Address(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull() ?: return@all false
            number in 0..255
        } && value != "127.0.0.1"
    }

    private suspend fun logContainerFileTail(
        containerName: String,
        path: String,
        logger: ContainerLogger?
    ) {
        val lines = runContainerCapture(containerName, "tail -n 30 ${shellQuote(path)} 2>/dev/null")
        if (lines.isEmpty()) return
        logger?.w("[!] Last lines from $path:")
        lines.forEach { line -> logger?.w(line) }
    }

    private fun probeContainer(containerName: String, command: String): Boolean =
        try {
            Shell.cmd(containerHostCommand(containerName, command) + " 2>/dev/null").exec().isSuccess
        } catch (_: Exception) {
            false
        }

    private fun runContainerCapture(containerName: String, command: String): List<String> =
        try {
            val result = Shell.cmd(containerHostCommand(containerName, command) + " 2>/dev/null").exec()
            if (result.isSuccess) result.out else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun runContainerCommand(
        containerName: String,
        title: String,
        command: String,
        logger: ContainerLogger?,
        logCommand: Boolean = true
    ): Boolean {
        logger?.i("[+] $title")
        if (logCommand) logger?.i("root@$containerName: $command")

        val hostCommand = containerHostCommand(containerName, command)
        val result = if (logger == null) {
            Shell.cmd(hostCommand).exec()
        } else {
            val stdout = object : CallbackList<String>() {
                override fun onAddElement(line: String) {
                    logger.logImmediate(Log.INFO, line)
                }
            }
            val stderr = object : CallbackList<String>() {
                override fun onAddElement(line: String) {
                    logger.logImmediate(Log.WARN, line)
                }
            }
            Shell.cmd(hostCommand).to(stdout, stderr).exec()
        }
        if (!result.isSuccess) {
            logger?.e("[-] FAIL (exit ${result.code})")
            logger?.i("")
            return false
        }
        logger?.i("[+] OK")
        logger?.i("")
        return true
    }

    private fun runContainerCommandRaw(containerName: String, command: String): Boolean =
        try {
            Shell.cmd(containerHostCommand(containerName, command)).exec().isSuccess
        } catch (_: Exception) {
            false
        }

    private fun containerHostCommand(containerName: String, command: String): String =
        "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run sh -c ${shellQuote(command)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
