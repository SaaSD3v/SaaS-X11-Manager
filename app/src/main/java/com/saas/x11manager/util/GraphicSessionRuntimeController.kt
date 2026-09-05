package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls only the graphical session process inside an already-running
 * DroidSpaces container. X11-0nly always targets display :0 / X0.
 *
 * OpenRC and systemd remain deliberately separate execution paths.
 */
internal object GraphicSessionRuntimeController {

    private const val INIT_MARKER = "__SAAS_X11_INIT__="
    private const val ACTION_MARKER = "__SAAS_X11_ACTION__="
    private const val DIAG_MARKER = "__SAAS_X11_DIAG__="
    private const val CONTAINER_X0_SOCKET = "/tmp/.X11-unix/X0"

    internal fun buildStartCommand(requireWaylandSocket: Boolean = false): String {
        val requireWayland = if (requireWaylandSocket) "1" else "0"
        return "expected=${shellQuote(CONTAINER_X0_SOCKET)}; display=${shellQuote(Constants.X11_DISPLAY)}; require_wayland=$requireWayland; " +
            "{ " +
            "if command -v systemctl >/dev/null 2>&1 && " +
            "test -f /etc/systemd/system/x11-session.service; then " +
            "printf '%s\\n' '${INIT_MARKER}systemd'; " +
            "setup_exit=0; " +
            "systemctl start setup-x11-socket.service >/dev/null 2>&1 || setup_exit=\$?; " +
            "printf '%s\\n' \"${ACTION_MARKER}socket-setup-exit=\$setup_exit\"; " +
            "if test -S \"\$expected\"; then " +
            "printf '%s\\n' '${ACTION_MARKER}socket-visible'; " +
            "else printf '%s\\n' '${ACTION_MARKER}socket-not-visible'; fi; " +
            "ready=0; " +
            "if systemctl is-active --quiet x11-session.service; then " +
            "printf '%s\\n' '${ACTION_MARKER}already-active'; ready=1; " +
            "else " +
            "printf '%s\\n' '${ACTION_MARKER}start-requested'; " +
            "{ systemctl reset-failed x11-session.service >/dev/null 2>&1 || true; }; " +
            "systemctl start x11-session.service >/dev/null 2>&1 || true; " +
            "fi; " +
            "attempt=0; " +
            "while [ \"\$ready\" -eq 0 ] && [ \"\$attempt\" -lt 10 ]; do " +
            "if systemctl is-active --quiet x11-session.service; then ready=1; break; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; " +
            "done; " +
            "if [ \"\$ready\" -eq 1 ]; then " +
            "printf '%s\\n' '${ACTION_MARKER}active'; true; else " +
            "printf '%s\\n' '${DIAG_MARKER}systemd session did not stabilize' >&2; " +
            "printf '%s\\n' \"${DIAG_MARKER}expected socket: \$expected\" >&2; " +
            "printf '%s\\n' \"${DIAG_MARKER}socket visible: \$(test -S \"\$expected\" && echo yes || echo no)\" >&2; " +
            "printf '%s\\n' \"${DIAG_MARKER}setup-x11-socket active: \$(systemctl is-active setup-x11-socket.service 2>/dev/null || true)\" >&2; " +
            "systemctl --no-pager --full status x11-session.service >&2 || true; " +
            "if command -v journalctl >/dev/null 2>&1; then " +
            "journalctl -u x11-session.service -n 24 --no-pager >&2 || true; fi; " +
            "false; fi; " +
            "elif command -v rc-service >/dev/null 2>&1 && " +
            "test -x /etc/init.d/x11-session; then " +
            "printf '%s\\n' '${INIT_MARKER}openrc'; " +
            "setup_exit=0; " +
            "rc-service x11-setup start >/dev/null 2>&1 || setup_exit=\$?; " +
            "printf '%s\\n' \"${ACTION_MARKER}socket-setup-exit=\$setup_exit\"; " +
            "if test -S \"\$expected\"; then " +
            "printf '%s\\n' '${ACTION_MARKER}socket-visible'; " +
            "else printf '%s\\n' '${ACTION_MARKER}socket-not-visible'; fi; " +
            "if ! command -v xset >/dev/null 2>&1; then " +
            "printf '%s\\n' '${ACTION_MARKER}x11-client-tool-missing'; " +
            "printf '%s\\n' '${DIAG_MARKER}xset is unavailable inside the container; the Manager cannot verify the X11 transport'; " +
            "exit 127; fi; " +
            "x11_client_ready=0; attempt=0; " +
            "while [ \"\$attempt\" -lt 10 ]; do " +
            "if DISPLAY=\"\$display\" xset q >/dev/null 2>&1; then x11_client_ready=1; break; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; done; " +
            "if [ \"\$x11_client_ready\" -eq 1 ]; then " +
            "printf '%s\\n' '${ACTION_MARKER}x11-client-ready'; " +
            "else " +
            "printf '%s\\n' '${ACTION_MARKER}x11-client-not-ready'; " +
            "printf '%s\\n' \"${DIAG_MARKER}X11 socket exists but an X11 client handshake failed on \$display\"; " +
            "printf '%s\\n' \"${DIAG_MARKER}expected socket: \$expected\"; " +
            "probe_output=\$(DISPLAY=\"\$display\" xset q 2>&1 || true); " +
            "[ -n \"\$probe_output\" ] && printf '%s\\n' \"${DIAG_MARKER}xset: \$probe_output\"; " +
            "exit 1; fi; " +
            "ready=0; " +
            "if rc-service x11-session status >/dev/null 2>&1; then " +
            "printf '%s\\n' '${ACTION_MARKER}already-active'; ready=1; " +
            "else " +
            "printf '%s\\n' '${ACTION_MARKER}start-requested'; " +
            "start_output=\$(rc-service x11-session start 2>&1); start_exit=\$?; " +
            "printf '%s\\n' \"${ACTION_MARKER}start-exit=\$start_exit\"; " +
            "[ -n \"\$start_output\" ] && printf '%s\\n' \"${DIAG_MARKER}openrc start: \$start_output\"; " +
            "fi; " +
            "attempt=0; " +
            "while [ \"\$ready\" -eq 0 ] && [ \"\$attempt\" -lt 10 ]; do " +
            "if rc-service x11-session status >/dev/null 2>&1; then ready=1; break; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; " +
            "done; " +
            "if [ \"\$ready\" -eq 1 ]; then " +
            "printf '%s\\n' '${ACTION_MARKER}active'; true; else " +
            "printf '%s\\n' '${DIAG_MARKER}openrc session did not stabilize'; " +
            "printf '%s\\n' \"${DIAG_MARKER}expected socket: \$expected\"; " +
            "printf '%s\\n' \"${DIAG_MARKER}socket visible: \$(test -S \"\$expected\" && echo yes || echo no)\"; " +
            "status_text=\$(rc-service x11-session status 2>&1 || true); " +
            "printf '%s\\n' \"${DIAG_MARKER}service status: \$status_text\"; " +
            "probe_output=\$(DISPLAY=\"\$display\" xset q 2>&1 || true); " +
            "[ -n \"\$probe_output\" ] && printf '%s\\n' \"${DIAG_MARKER}xset after session failure: \$probe_output\"; " +
            "false; fi; " +
            "else echo 'No Manager x11-session service is provisioned' >&2; exit 127; fi; " +
            "}; service_rc=\$?; [ \"\$service_rc\" -eq 0 ] || exit \"\$service_rc\"; " +
            "if [ \"\$require_wayland\" -eq 1 ]; then " +
            "wayland_ready=0; attempt=0; " +
            "while [ \"\$attempt\" -lt 10 ]; do " +
            "for wl in /tmp/runtime-root/wayland-*; do " +
            "if [ -S \"\$wl\" ]; then wayland_ready=1; break; fi; done; " +
            "[ \"\$wayland_ready\" -eq 1 ] && break; " +
            "attempt=\$((attempt + 1)); sleep 1; done; " +
            "if [ \"\$wayland_ready\" -eq 1 ]; then " +
            "printf '%s\\n' '${ACTION_MARKER}wayland-visible'; " +
            "else printf '%s\\n' '${ACTION_MARKER}wayland-not-visible'; " +
            "printf '%s\\n' '${DIAG_MARKER}Wayland compositor service is active but no socket appeared in /tmp/runtime-root' >&2; " +
            "exit 1; fi; fi"
    }

    internal fun buildStopCommand(): String =
        "if command -v systemctl >/dev/null 2>&1 && " +
            "test -f /etc/systemd/system/x11-session.service; then " +
            "printf '%s\\n' '${INIT_MARKER}systemd'; " +
            "systemctl stop x11-session.service; " +
            "elif command -v rc-service >/dev/null 2>&1 && " +
            "test -x /etc/init.d/x11-session; then " +
            "printf '%s\\n' '${INIT_MARKER}openrc'; " +
            "if rc-service x11-session status >/dev/null 2>&1; then " +
            "rc-service x11-session stop; else true; fi; " +
            "else echo 'No Manager x11-session service is provisioned' >&2; exit 127; fi"

    suspend fun ensureRunning(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val configuredSession = ContainerSettingsManager
            .readSnapshot(containerName)
            .graphicSession

        logger?.i("--- Graphic Session Synchronization ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Host display: ${Constants.X11_DISPLAY}")
        logger?.i("[CTX] Host transport: Integrated X11")
        logger?.i("[CTX] Expected container socket: $CONTAINER_X0_SOCKET")
        logger?.i("[CTX] Container lifecycle: unchanged")

        if (configuredSession == null || configuredSession == GraphicSession.NONE) {
            logger?.i("[*] No managed graphic session is configured for $containerName")
            logger?.i("[+] Leaving ${Constants.X11_DISPLAY} available as a raw X11 server")
            return@withContext true
        }

        logger?.i("[CTX] Protocol: ${configuredSession.protocol.label}")
        logger?.i("[CTX] Configured graphic session: ${configuredSession.label}")
        logger?.i("[*] Ensuring ${configuredSession.label} session on ${Constants.X11_DISPLAY}...")
        val startedAt = System.nanoTime()
        var result = runContainerCommand(
            containerName,
            buildStartCommand(
                requireWaylandSocket = configuredSession.protocol == GraphicProtocol.WAYLAND
            )
        )
        logRuntimeMarkers(result.out, logger)

        val transportHandshakeFailed = result.out.any {
            it.trim() == "${ACTION_MARKER}x11-client-not-ready"
        }
        if (transportHandshakeFailed) {
            logger?.w("[!] ${Constants.X11_DISPLAY} rejected a real X11 client; restarting the fixed Manager X11 server once")
            val stopped = X11SessionManager.stopIntegratedServer(logger)
            if (stopped) {
                val restarted = X11SessionManager.startIntegratedServer(
                    containerName = containerName,
                    logger = logger
                )
                if (restarted.isSuccess) {
                    logger?.i("[*] Retrying ${configuredSession.label} after X11 transport restart...")
                    result = runContainerCommand(
                        containerName,
                        buildStartCommand(
                            requireWaylandSocket = configuredSession.protocol == GraphicProtocol.WAYLAND
                        )
                    )
                    logRuntimeMarkers(result.out, logger)
                }
            }
        }

        logger?.i("[CTX] Controller exit code: ${result.code}")
        logger?.i("[CTX] Synchronization duration: ${(System.nanoTime() - startedAt) / 1_000_000L}ms")

        if (result.isSuccess) {
            logger?.i("[+] ${configuredSession.label} ${configuredSession.protocol.label} session active on ${Constants.X11_DISPLAY}")
            true
        } else {
            logger?.w("[!] ${configuredSession.label} could not be confirmed active on ${Constants.X11_DISPLAY} (exit ${result.code})")
            logFailureDiagnostics(result.out, result.err, logger)
            false
        }
    }

    suspend fun stop(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val configuredSession = ContainerSettingsManager
            .readSnapshot(containerName)
            .graphicSession

        logger?.i("--- Graphic Session Stop ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Container lifecycle: remains RUNNING")

        if (configuredSession == null || configuredSession == GraphicSession.NONE) {
            logger?.i("[+] No managed graphic session process needs to be stopped")
            return@withContext true
        }

        logger?.i("[*] Stopping ${configuredSession.label} graphic session only...")
        val startedAt = System.nanoTime()
        val result = runContainerCommand(containerName, buildStopCommand())
        logRuntimeMarkers(result.out, logger)
        logger?.i("[CTX] Controller exit code: ${result.code}")
        logger?.i("[CTX] Stop duration: ${(System.nanoTime() - startedAt) / 1_000_000L}ms")

        if (result.isSuccess) {
            logger?.i("[+] Graphic session stopped; container remains running")
            true
        } else {
            logger?.w("[!] Could not confirm graphic session stopped (exit ${result.code})")
            logFailureDiagnostics(result.out, result.err, logger)
            false
        }
    }

    private fun runContainerCommand(containerName: String, command: String) = Shell.cmd(
        "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
            "sh -c ${shellQuote(command)}"
    ).exec()

    private suspend fun logRuntimeMarkers(output: List<String>, logger: ContainerLogger?) {
        output.firstOrNull { it.startsWith(INIT_MARKER) }
            ?.removePrefix(INIT_MARKER)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { logger?.i("[+] Graphic session backend: $it") }

        val actions = output.asSequence()
            .filter { it.startsWith(ACTION_MARKER) }
            .map { it.removePrefix(ACTION_MARKER).trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

        for (action in actions) {
            when {
                action == "socket-not-visible" -> logger?.w("[!] Container X11 transport socket was not visible during the prerequisite check")
                action.startsWith("socket-setup-exit=") && action != "socket-setup-exit=0" -> logger?.w("[!] X11 transport socket setup service returned ${action.substringAfter('=')}")
                action == "socket-visible" -> logger?.i("[+] Expected container X11 transport socket is visible")
                action == "x11-client-ready" -> logger?.i("[+] Container completed a real X11 client handshake")
                action == "x11-client-not-ready" -> logger?.w("[!] X11 socket is visible, but a real X11 client cannot connect")
                action == "x11-client-tool-missing" -> logger?.w("[!] xset is missing; X11 client readiness cannot be verified")
                action == "wayland-visible" -> logger?.i("[+] Wayland compositor socket is visible")
                action == "wayland-not-visible" -> logger?.w("[!] Wayland compositor socket did not appear")
                action == "already-active" -> logger?.i("[+] Graphic session service was already active; preserving it")
                action == "start-requested" -> logger?.i("[*] Graphic session service was inactive; start requested")
                action.startsWith("start-exit=") && action != "start-exit=0" -> logger?.w("[!] OpenRC graphical session start returned ${action.substringAfter('=')}")
                action == "active" -> logger?.i("[+] Graphic session service confirmed active")
                else -> logger?.i("[CTX] Graphic session action: $action")
            }
        }
    }

    private suspend fun logFailureDiagnostics(
        stdout: List<String>,
        stderr: List<String>,
        logger: ContainerLogger?
    ) {
        val lines = (stdout + stderr)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith(INIT_MARKER) && !it.startsWith(ACTION_MARKER) }
            .map { it.removePrefix(DIAG_MARKER) }
            .takeLast(24)

        if (lines.isEmpty()) return
        logger?.w("[!] Graphic session diagnostics:")
        lines.forEach { logger?.w("[!] $it") }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
