package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runtime-only preparation for Free mode.
 *
 * This deliberately installs nothing. It only exposes the Manager-owned socket
 * at the conventional /tmp/.X11-unix path inside the already-running container.
 */
internal object FreeX11Runtime {

    suspend fun prepare(
        containerName: String,
        displayName: String,
        socketFileName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val containerSocket = "/tmp/.X11-unix/$socketFileName"
        val command = X11SessionCommands.socketSetup() + "\n" +
            "test -S ${shellQuote(containerSocket)}"

        val result = try {
            Shell.cmd(
                "${Constants.DS_BINARY_PATH} --name=${shellQuote(containerName)} run " +
                    "sh -c ${shellQuote(command)}"
            ).exec()
        } catch (e: Exception) {
            logger?.e("[FREE] Could not prepare raw X11 transport: ${e.message}")
            return@withContext false
        }

        if (!result.isSuccess) {
            logger?.e("[FREE] Raw X11 socket preparation failed (exit ${result.code})")
            (result.out + result.err)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeLast(12)
                .forEach { logger?.w("[FREE] $it") }
            return@withContext false
        }

        logger?.i("[FREE] Raw X11 transport ready")
        logger?.i("[FREE] Container socket: $containerSocket")
        logger?.i("[FREE] Run inside the container: ${exportCommand(displayName)}")
        true
    }

    internal fun exportCommand(displayName: String): String =
        "export DISPLAY=$displayName"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
