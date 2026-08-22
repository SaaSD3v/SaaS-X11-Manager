package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls only the Manager-provisioned graphical session inside an already-running
 * DroidSpaces container. It never starts or stops the container itself and it never
 * owns the host X11 server process.
 *
 * This branch is intentionally single-display, so the expected in-container socket
 * is /tmp/.X11-unix/X0. Backend discovery remains capability-driven: use whichever
 * Manager-provisioned service actually exists (systemd or OpenRC).
 */
internal object GraphicSessionRuntimeController {

    private const val INIT_MARKER = "__SAAS_X11_INIT__="
    private const val ACTION_MARKER = "__SAAS_X11_ACTION__="
    private const val DIAG_MARKER = "__SAAS_X11_DIAG__="
    private const val EXPECTED_CONTAINER_SOCKET = "/tmp/.X11-unix/X0"

    internal fun buildStartCommand(): String {
        val expected = shellQuote(EXPECTED_CONTAINER_SOCKET)
        return "expected=$expected; " +
            "if command -v systemctl >/dev/null 2>&1 && " +
            "test -f /etc/systemd/system/x11-session.service && " +
            "test -f /etc/systemd/system/setup-x11-socket.service; then " +
            "printf '%s\\n' '${INIT_MARKER}systemd'; " +
            "systemctl start setup-x11-socket.service >/dev/null 2>&1 || true; " +
            "if ! test -S \"\$expected\"; then systemctl restart setup-x11-socket.service >/dev/null 2>&1 || true; fi; " +
            "if ! test -S \"\$expected\"; then " +
            "printf '%s\\n' '${ACTION_MARKER}socket-not-visible'; " +
            "printf '%s\\n' \"${DIAG_MARKER}expected socket missing: \$expected\" >&2; exit 41; fi; " +
            "printf '%s\\n' '${ACTION_MARKER}socket-visible'; " +
            "if systemctl is-active --quiet x11-session.service; then " +
            "printf '%s\\n' '${ACTION_MARKER}already-active'; exit 0; fi; " +
            "printf '%s\\n' '${ACTION_MARKER}start-requested'; " +
            "systemctl reset-failed x11-session.service >/dev/null 2>&1 || true; " +
            "systemctl start x11-session.service >/dev/null 2>&1 || true; " +
            "attempt=0; while [ \"\$attempt\" -lt 10 ]; do " +
            "if systemctl is-active --quiet x11-session.service; then " +
            "printf '%s\\n' '${ACTION_MARKER}active'; exit 0; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; done; " +
            "printf '%s\\n' '${DIAG_MARKER}systemd x11-session did not stabilize' >&2; " +
            "systemctl --no-pager --full status x11-session.service >&2 || true; " +
            "if command -v journalctl >/dev/null 2>&1; then journalctl -u x11-session.service -n 24 --no-pager >&2 || true; fi; exit 42; " +
            "elif command -v rc-service >/dev/null 2>&1 && " +
            "test -x /etc/init.d/x11-session && test -x /etc/init.d/x11-setup; then " +
            "printf '%s\\n' '${INIT_MARKER}openrc'; " +
            "rc-service x11-setup start >/dev/null 2>&1 || true; " +
            "if ! test -S \"\$expected\"; then rc-service x11-setup restart >/dev/null 2>&1 || true; fi; " +
            "if ! test -S \"\$expected\"; then " +
            "printf '%s\\n' '${ACTION_MARKER}socket-not-visible'; " +
            "printf '%s\\n' \"${DIAG_MARKER}expected socket missing: \$expected\" >&2; exit 41; fi; " +
            "printf '%s\\n' '${ACTION_MARKER}socket-visible'; " +
            "if rc-service x11-session status >/dev/null 2>&1; then " +
            "printf '%s\\n' '${ACTION_MARKER}already-active'; exit 0; fi; " +
            "printf '%s\\n' '${ACTION_MARKER}start-requested'; " +
            "rc-service x11-session start >/dev/null 2>&1 || true; " +
            "attempt=0; while [ \"\$attempt\" -lt 10 ]; do " +
            "if rc-service x11-session status >/dev/null 2>&1; then " +
            "printf '%s\\n' '${ACTION_MARKER}active'; exit 0; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; done; " +
            "printf '%s\\n' '${DIAG_MARKER}OpenRC x11-session did not stabilize' >&2; " +
            "rc-service x11-session status >&2 || true; exit 42; " +
            "else printf '%s\\n' '${INIT_MARKER}none'; " +
            "printf '%s\\n' '${DIAG_MARKER}no Manager x11-session backend is provisioned' >&2; exit 127; fi"
    }

    internal fun buildStopCommand(): String =
        "if command -v systemctl >/dev/null 2>&1 && test -f /etc/systemd/system/x11-session.service; then " +
            "printf '%s\\n' '${INIT_MARKER}systemd'; " +
            "systemctl stop x11-session.service; rc=\$?; " +
            "systemctl reset-failed x11-session.service >/dev/null 2>&1 || true; exit \$rc; " +
            "elif command -v rc-service >/dev/null 2>&1 && test -x /etc/init.d/x11-session; then " +
            "printf '%s\\n' '${INIT_MARKER}openrc'; " +
            "if rc-service x11-session status >/dev/null 2>&1; then rc-service x11-session stop; else true; fi; " +
            "else printf '%s\\n' '${INIT_MARKER}none'; exit 0; fi"

    suspend fun ensureRunning(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val configuredSession = ContainerSettingsManager.readSnapshot(containerName).graphicSession

        logger?.i("--- Graphic Session Synchronization ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Display: ${Constants.X11_DISPLAY}")
        logger?.i("[CTX] Expected container socket: $EXPECTED_CONTAINER_SOCKET")
        logger?.i("[CTX] Container lifecycle: unchanged")

        if (configuredSession == GraphicSession.NONE) {
            logger?.i("[CTX] Configured graphic session: none")
            logger?.i("[+] Raw X11 requested; no managed desktop/window manager will be started")
            return@withContext true
        }

        if (configuredSession == null) {
            logger?.i("[CTX] Configured graphic session metadata: absent")
            logger?.i("[+] Leaving legacy/init-owned graphic session state unchanged")
            return@withContext true
        }

        logger?.i("[CTX] Configured graphic session: ${configuredSession.label}")
        logger?.i("[*] Ensuring ${configuredSession.label} is active on ${Constants.X11_DISPLAY}...")
        val startedAt = System.nanoTime()
        val result = runContainerCommand(containerName, buildStartCommand())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logRuntimeMarkers(result.out, logger)
        logger?.i("[CTX] Graphic session controller exit code: ${result.code}")
        logger?.i("[CTX] Synchronization duration: ${elapsedMs}ms")

        if (result.isSuccess) {
            logger?.i("[+] ${configuredSession.label} graphic session confirmed active")
            true
        } else {
            logger?.e("[-] ${configuredSession.label} graphic session could not be confirmed active")
            logFailureDiagnostics(result.out, result.err, logger)
            false
        }
    }

    suspend fun stop(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        logger?.i("--- Graphic Session Stop ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Container lifecycle: remains RUNNING")
        logger?.i("[*] Stopping Manager x11-session service only...")

        val startedAt = System.nanoTime()
        val result = runContainerCommand(containerName, buildStopCommand())
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logRuntimeMarkers(result.out, logger)
        logger?.i("[CTX] Graphic session stop exit code: ${result.code}")
        logger?.i("[CTX] Stop duration: ${elapsedMs}ms")

        if (result.isSuccess) {
            logger?.i("[+] Graphic session stopped; DroidSpaces container remains running")
            true
        } else {
            logger?.w("[!] Could not confirm Manager graphic session stopped")
            logFailureDiagnostics(result.out, result.err, logger)
            false
        }
    }

    private fun runContainerCommand(containerName: String, command: String) =
        Shell.cmd(
            "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                "sh -c ${shellQuote(command)}"
        ).exec()

    private suspend fun logRuntimeMarkers(output: List<String>, logger: ContainerLogger?) {
        output.firstOrNull { it.startsWith(INIT_MARKER) }
            ?.removePrefix(INIT_MARKER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { logger?.i("[+] Graphic session backend: $it") }

        output.asSequence()
            .filter { it.startsWith(ACTION_MARKER) }
            .map { it.removePrefix(ACTION_MARKER).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
            .forEach { action ->
                when (action) {
                    "socket-visible" -> logger?.i("[+] Container X11 socket is visible")
                    "socket-not-visible" -> logger?.e("[-] Container X11 socket is not visible")
                    "already-active" -> logger?.i("[+] Graphic session service already active; preserving it")
                    "start-requested" -> logger?.i("[*] Graphic session service was inactive; start requested")
                    "active" -> logger?.i("[+] Graphic session service confirmed active")
                    else -> logger?.i("[CTX] Graphic session action: $action")
                }
            }
    }

    private suspend fun logFailureDiagnostics(
        stdout: List<String>,
        stderr: List<String>,
        logger: ContainerLogger?
    ) {
        (stdout + stderr)
            .asSequence()
            .map(String::trim)
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith(INIT_MARKER) &&
                    !it.startsWith(ACTION_MARKER)
            }
            .map { it.removePrefix(DIAG_MARKER) }
            .toList()
            .takeLast(24)
            .forEach { logger?.w("[!] $it") }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
