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
 * monitor restart independent from distro names and fixed platform versions.
 */
internal object GraphicSessionRuntimeController {

    private const val INIT_MARKER = "__SAAS_X11_INIT__="

    internal fun buildStartCommand(displaySlot: X11DisplaySlot): String {
        val expectedSocket = "/tmp/.X11-unix/X${displaySlot.number}"
        return "expected=${shellQuote(expectedSocket)}; " +
            "if command -v systemctl >/dev/null 2>&1 && " +
            "test -f /etc/systemd/system/x11-session.service; then " +
            "printf '%s\\n' '${INIT_MARKER}systemd'; " +
            "systemctl start setup-x11-socket.service && " +
            "test -S \"\$expected\" && " +
            "{ systemctl reset-failed x11-session.service >/dev/null 2>&1 || true; } && " +
            "systemctl restart x11-session.service && " +
            "systemctl is-active --quiet x11-session.service; " +
            "elif command -v rc-service >/dev/null 2>&1 && " +
            "test -x /etc/init.d/x11-session; then " +
            "printf '%s\\n' '${INIT_MARKER}openrc'; " +
            "rc-service x11-setup start && " +
            "test -S \"\$expected\" && " +
            "{ if rc-service x11-session status >/dev/null 2>&1; then " +
            "rc-service x11-session restart; else rc-service x11-session start; fi; } && " +
            "rc-service x11-session status >/dev/null 2>&1; " +
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
                    "could not be started (exit ${result.code})"
            )
            result.err.lastOrNull()?.takeIf { it.isNotBlank() }?.let { logger?.w("[!] $it") }
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

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
