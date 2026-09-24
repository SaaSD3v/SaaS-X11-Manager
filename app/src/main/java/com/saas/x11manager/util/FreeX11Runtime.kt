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
            logger?.e("[X11] ✗ Could not prepare Free raw transport: ${e.message}")
            return@withContext false
        }

        if (!result.isSuccess) {
            logger?.e("[X11] ✗ Free raw socket preparation failed (exit ${result.code})")
            (result.out + result.err)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .takeLast(12)
                .forEach { logger?.w("[X11] ! $it") }
            return@withContext false
        }

        logger?.i("[X11] ✓ Free raw transport ready")
        logger?.i("[X11] • Container socket: $containerSocket")
        true
    }

    internal fun exportCommand(displayName: String): String =
        "export DISPLAY=$displayName"

    internal fun unsetCommand(): String = "unset DISPLAY"

    internal fun replaceCommand(displayName: String): String =
        "${unsetCommand()}; ${exportCommand(displayName)}"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
