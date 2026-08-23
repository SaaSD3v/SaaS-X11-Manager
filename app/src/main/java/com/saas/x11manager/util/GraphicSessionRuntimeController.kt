package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls only the graphical session process inside an already-running
 * DroidSpaces container. It never starts or stops the container itself.
 *
 * The service names remain backward-compatible with the original X11 manager,
 * while protocol health checks are selected from the configured session.
 */
internal object GraphicSessionRuntimeController {

    private const val INIT_MARKER = "__SAAS_X11_INIT__="
    private const val ACTION_MARKER = "__SAAS_X11_ACTION__="
    private const val DIAG_MARKER = "__SAAS_X11_DIAG__="

    internal fun buildStartCommand(
        displaySlot: X11DisplaySlot,
        requireWaylandSocket: Boolean = false
    ): String {
        val expectedSocket = "/tmp/.X11-unix/X${displaySlot.number}"
        val requireWayland = if (requireWaylandSocket) "1" else "0"
        return "expected=${shellQuote(expectedSocket)}; require_wayland=$requireWayland; " +
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
            "ready=0; " +
            "if rc-service x11-session status >/dev/null 2>&1; then " +
            "printf '%s\\n' '${ACTION_MARKER}already-active'; ready=1; " +
            "else " +
            "printf '%s\\n' '${ACTION_MARKER}start-requested'; " +
            "rc-service x11-session start >/dev/null 2>&1 || true; " +
            "fi; " +
            "attempt=0; " +
            "while [ \"\$ready\" -eq 0 ] && [ \"\$attempt\" -lt 10 ]; do " +
            "if rc-service x11-session status >/dev/null 2>&1; then ready=1; break; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; " +
            "done; " +
            "if [ \"\$ready\" -eq 1 ]; then " +
            "printf '%s\\n' '${ACTION_MARKER}active'; true; else " +
            "printf '%s\\n' '${DIAG_MARKER}openrc session did not stabilize' >&2; " +
            "printf '%s\\n' \"${DIAG_MARKER}expected socket: \$expected\" >&2; " +
            "printf '%s\\n' \"${DIAG_MARKER}socket visible: \$(test -S \"\$expected\" && echo yes || echo no)\" >&2; " +
            "rc-service x11-session status >&2 || true; false; fi; " +
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
        displaySlot: X11DisplaySlot,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val configuredSession = ContainerSettingsManager
            .readSnapshot(containerName)
            .graphicSession

        logger?.i("--- Graphic Session Synchronization ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Monitor: ${displaySlot.monitorNumber}")
        logger?.i("[CTX] Host display: ${displaySlot.displayName}")
        logger?.i("[CTX] Host transport: Integrated X11")
        logger?.i("[CTX] Expected container socket: /tmp/.X11-unix/X${displaySlot.number}")
        logger?.i("[CTX] Container lifecycle: unchanged")

        if (configuredSession == null || configuredSession == GraphicSession.NONE) {
            logger?.i("[CTX] Configured graphic session: none")
            logger?.i("[*] No managed graphic session is configured for $containerName")
            logger?.i("[+] Leaving ${displaySlot.describe()} available as a raw X11 server")
            return@withContext true
        }

        logger?.i("[CTX] Protocol: ${configuredSession.protocol.label}")
        logger?.i("[CTX] Configured graphic session: ${configuredSession.label}")
        logger?.i("[CTX] Session command: ${configuredSession.startCommand}")
        if (configuredSession.protocol == GraphicProtocol.WAYLAND) {
            logger?.i("[CTX] Wayland runtime: /tmp/runtime-root")
        }
        logger?.i("[*] Ensuring ${configuredSession.label} session on ${displaySlot.describe()}...")
        val startedAt = System.nanoTime()
        val result = runContainerCommand(
            containerName,
            buildStartCommand(
                displaySlot = displaySlot,
                requireWaylandSocket = configuredSession.protocol == GraphicProtocol.WAYLAND
            )
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logRuntimeMarkers(result.out, logger)
        logger?.i("[CTX] Controller exit code: ${result.code}")
        logger?.i("[CTX] Synchronization duration: ${elapsedMs}ms")

        if (result.isSuccess) {
            logger?.i(
                "[+] ${configuredSession.label} ${configuredSession.protocol.label} session active on " +
                    displaySlot.describe()
            )
            true
        } else {
            logger?.w(
                "[!] ${displaySlot.describe()} is ready, but ${configuredSession.label} " +
                    "could not be confirmed active (exit ${result.code})"
            )
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
            logger?.i("[CTX] Configured graphic session: none")
            logger?.i("[+] No managed graphic session process needs to be stopped")
            return@withContext true
        }

        logger?.i("[CTX] Protocol: ${configuredSession.protocol.label}")
        logger?.i("[CTX] Configured graphic session: ${configuredSession.label}")
        logger?.i("[CTX] Session command: ${configuredSession.startCommand}")
        logger?.i("[*] Stopping ${configuredSession.label} graphic session only...")
        val startedAt = System.nanoTime()
        val result = runContainerCommand(containerName, buildStopCommand())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logRuntimeMarkers(result.out, logger)
        logger?.i("[CTX] Controller exit code: ${result.code}")
        logger?.i("[CTX] Stop duration: ${elapsedMs}ms")

        if (result.isSuccess) {
            logger?.i("[+] Graphic session stopped; container remains running")
            true
        } else {
            logger?.w("[!] Could not confirm graphic session stopped (exit ${result.code})")
            logFailureDiagnostics(result.out, result.err, logger)
            false
        }
    }

    private fun runContainerCommand(
        containerName: String,
        command: String
    ) = Shell.cmd(
        "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
            "sh -c ${shellQuote(command)}"
    ).exec()

    private suspend fun logRuntimeMarkers(
        output: List<String>,
        logger: ContainerLogger?
    ) {
        output
            .firstOrNull { it.startsWith(INIT_MARKER) }
            ?.removePrefix(INIT_MARKER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { backend -> logger?.i("[+] Graphic session backend: $backend") }

        val actions = output
            .asSequence()
            .filter { it.startsWith(ACTION_MARKER) }
            .map { it.removePrefix(ACTION_MARKER).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        for (action in actions) {
            when {
                action == "socket-not-visible" ->
                    logger?.w("[!] Container X11 transport socket was not visible during the prerequisite check")
                action.startsWith("socket-setup-exit=") && action != "socket-setup-exit=0" ->
                    logger?.w("[!] X11 transport socket setup service returned ${action.substringAfter('=')}")
                action == "socket-visible" ->
                    logger?.i("[+] Expected container X11 transport socket is visible")
                action == "wayland-visible" ->
                    logger?.i("[+] Wayland compositor socket is visible")
                action == "wayland-not-visible" ->
                    logger?.w("[!] Wayland compositor socket did not appear")
                action == "already-active" ->
                    logger?.i("[+] Graphic session service was already active; preserving it")
                action == "start-requested" ->
                    logger?.i("[*] Graphic session service was inactive; start requested")
                action == "active" ->
                    logger?.i("[+] Graphic session service confirmed active")
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
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith(INIT_MARKER) &&
                    !it.startsWith(ACTION_MARKER)
            }
            .map { it.removePrefix(DIAG_MARKER) }
            .takeLast(24)

        if (lines.isEmpty()) return
        logger?.w("[!] Graphic session diagnostics:")
        lines.forEach { logger?.w("[!] $it") }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
