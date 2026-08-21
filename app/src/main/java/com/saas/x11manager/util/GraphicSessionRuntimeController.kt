package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls only the graphical session process inside an already-running
 * DroidSpaces container. It never starts or stops the container itself.
 *
 * Runtime service discovery is capability-driven: whichever Manager-provisioned
 * x11-session backend actually exists (systemd or OpenRC) is used. This keeps a
 * monitor restart independent from distro names, graphical session names, fixed
 * platform versions, and X11 display numbers.
 */
internal object GraphicSessionRuntimeController {

    private const val INIT_MARKER = "__SAAS_X11_INIT__="
    private const val DIAG_MARKER = "__SAAS_X11_DIAG__="

    internal fun buildStartCommand(displaySlot: X11DisplaySlot): String {
        val expectedSocket = "/tmp/.X11-unix/X${displaySlot.number}"
        return "expected=${shellQuote(expectedSocket)}; " +
            "if command -v systemctl >/dev/null 2>&1 && " +
            "test -f /etc/systemd/system/x11-session.service; then " +
            "printf '%s\\n' '${INIT_MARKER}systemd'; " +
            "systemctl start setup-x11-socket.service && " +
            "test -S \"\$expected\" && " +
            "{ systemctl reset-failed x11-session.service >/dev/null 2>&1 || true; } && " +
            "{ " +
            // A fast first launch can fail while systemd schedules the unit's
            // configured Restart=on-failure retry. Do not turn that transient
            // restart into a false X11 failure: wait for the unit to stabilize.
            "systemctl restart x11-session.service >/dev/null 2>&1 || true; " +
            "ready=0; attempt=0; " +
            "while [ \"\$attempt\" -lt 10 ]; do " +
            "if systemctl is-active --quiet x11-session.service; then ready=1; break; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; " +
            "done; " +
            "if [ \"\$ready\" -eq 1 ]; then true; else " +
            "printf '%s\\n' '${DIAG_MARKER}systemd session did not stabilize' >&2; " +
            "systemctl --no-pager --full status x11-session.service >&2 || true; " +
            "if command -v journalctl >/dev/null 2>&1; then " +
            "journalctl -u x11-session.service -n 24 --no-pager >&2 || true; fi; " +
            "false; fi; " +
            "}; " +
            "elif command -v rc-service >/dev/null 2>&1 && " +
            "test -x /etc/init.d/x11-session; then " +
            "printf '%s\\n' '${INIT_MARKER}openrc'; " +
            "rc-service x11-setup start && " +
            "test -S \"\$expected\" && " +
            "{ " +
            "if rc-service x11-session status >/dev/null 2>&1; then " +
            "rc-service x11-session restart >/dev/null 2>&1 || true; " +
            "else rc-service x11-session start >/dev/null 2>&1 || true; fi; " +
            "ready=0; attempt=0; " +
            "while [ \"\$attempt\" -lt 10 ]; do " +
            "if rc-service x11-session status >/dev/null 2>&1; then ready=1; break; fi; " +
            "attempt=\$((attempt + 1)); sleep 1; " +
            "done; " +
            "if [ \"\$ready\" -eq 1 ]; then true; else " +
            "printf '%s\\n' '${DIAG_MARKER}openrc session did not stabilize' >&2; " +
            "rc-service x11-session status >&2 || true; false; fi; " +
            "}; " +
            "else echo 'No Manager x11-session service is provisioned' >&2; exit 127; fi"
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

        if (configuredSession == null || configuredSession == GraphicSession.NONE) {
            logger?.i("[*] No managed graphic session is configured for $containerName")
            logger?.i("[+] Leaving ${displaySlot.describe()} available as a raw X11 server")
            return@withContext true
        }

        logger?.i("[*] Ensuring ${configuredSession.label} session on ${displaySlot.describe()}...")
        val result = runContainerCommand(containerName, buildStartCommand(displaySlot))
        logDetectedBackend(result.out, logger)

        if (result.isSuccess) {
            logger?.i("[+] ${configuredSession.label} session active on ${displaySlot.describe()}")
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

        if (configuredSession == null || configuredSession == GraphicSession.NONE) {
            return@withContext true
        }

        logger?.i("[*] Stopping ${configuredSession.label} graphic session only...")
        val result = runContainerCommand(containerName, buildStopCommand())
        logDetectedBackend(result.out, logger)

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

    private suspend fun logDetectedBackend(
        output: List<String>,
        logger: ContainerLogger?
    ) {
        val backend = output
            .firstOrNull { it.startsWith(INIT_MARKER) }
            ?.removePrefix(INIT_MARKER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        logger?.i("[+] Graphic session backend: $backend")
    }

    private suspend fun logFailureDiagnostics(
        stdout: List<String>,
        stderr: List<String>,
        logger: ContainerLogger?
    ) {
        val lines = (stdout + stderr)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith(INIT_MARKER) }
            .map { it.removePrefix(DIAG_MARKER) }
            .takeLast(24)

        if (lines.isEmpty()) return
        logger?.w("[!] Graphic session diagnostics:")
        lines.forEach { logger?.w("[!] $it") }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
