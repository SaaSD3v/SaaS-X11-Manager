package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the minimum DroidSpaces container.config changes required by the
 * single-display integrated X11 backend.
 *
 * X11-0nly has exactly one Manager transport: display :0. Any existing bind
 * targeting /usr/.X11-unix is replaced by the fixed Manager X0 socket directory.
 */
object ContainerConfigManager {

    private const val X11_CONTAINER_SOCKET_DIR = "/usr/.X11-unix"
    private val requiredBind: String
        get() = "${Constants.X11_SOCK_DIR}:$X11_CONTAINER_SOCKET_DIR"

    suspend fun ensureManualX11Config(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val configPath = "${Constants.CONTAINERS_DIR}/$containerName/${Constants.CONFIG_FILE}"

        logger?.i("--- X11 Container Configuration ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Config: $configPath")
        logger?.i("[CTX] Display: ${Constants.X11_DISPLAY}")
        logger?.i("[CTX] Host socket directory: ${Constants.X11_SOCK_DIR}")
        logger?.i("[CTX] Container socket directory: $X11_CONTAINER_SOCKET_DIR")
        logger?.i("[CTX] Required bind: $requiredBind")
        logger?.i("[CTX] DroidSpaces Termux:X11 integration: disabled (Manager owns X11)")

        try {
            val read = Shell.cmd("cat ${shellQuote(configPath)} 2>/dev/null").exec()
            if (!read.isSuccess || read.out.isEmpty()) {
                logger?.e("[-] Cannot read container config")
                return@withContext false
            }

            val original = read.out.toList()
            val updated = buildManualX11Config(original)
            val originalText = original.joinToString("\n") + "\n"
            val updatedText = updated.joinToString("\n") + "\n"

            if (updatedText == originalText) {
                logger?.i("[+] Integrated X11 container config already ready for ${Constants.X11_DISPLAY}")
                return@withContext true
            }

            val tempPath = "$configPath.saas-x11.tmp.${System.nanoTime()}"
            val write = Shell.cmd(
                "printf '%s' ${shellQuote(updatedText)} > ${shellQuote(tempPath)} && " +
                    "chmod 644 ${shellQuote(tempPath)} && " +
                    "mv -f ${shellQuote(tempPath)} ${shellQuote(configPath)}"
            ).exec()

            if (!write.isSuccess) {
                Shell.cmd("rm -f ${shellQuote(tempPath)} 2>/dev/null").exec()
                logger?.e("[-] Failed to update integrated X11 container config")
                return@withContext false
            }

            logger?.i("[+] Integrated X11 container config ready for ${Constants.X11_DISPLAY}")
            true
        } catch (e: Exception) {
            logger?.e("[-] X11 config error: ${e.message}")
            false
        }
    }

    internal fun buildManualX11Config(original: List<String>): List<String> {
        val updated = original.toMutableList()
        var x11FlagFound = false
        var bindMountsFound = false

        for (index in updated.indices) {
            val trimmed = updated[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains('=')) continue

            when (trimmed.substringBefore('=').trim()) {
                "enable_termux_x11" -> {
                    x11FlagFound = true
                    updated[index] = "enable_termux_x11=0"
                }

                "bind_mounts" -> {
                    bindMountsFound = true
                    val entries = trimmed.substringAfter('=', "")
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .filterNot { bindDestination(it) == X11_CONTAINER_SOCKET_DIR }
                        .toMutableList()

                    entries.add(requiredBind)
                    updated[index] = "bind_mounts=${entries.distinct().joinToString(",")}"
                }
            }
        }

        if (!x11FlagFound) updated.add("enable_termux_x11=0")
        if (!bindMountsFound) updated.add("bind_mounts=$requiredBind")
        return updated
    }

    /** True when a config already points the container at the fixed Manager X0 socket. */
    internal fun usesManagedX11(bindMounts: String): Boolean = bindMounts
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .any { entry ->
            bindDestination(entry) == X11_CONTAINER_SOCKET_DIR &&
                entry.substringBefore(':').trim() == Constants.X11_SOCK_DIR
        }

    /**
     * True for any X11 socket bind. Used only as a fail-closed guard while a
     * container is already running: a live config cannot be safely rewritten.
     */
    internal fun hasAnyX11SocketBind(bindMounts: String): Boolean = bindMounts
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .any { bindDestination(it) == X11_CONTAINER_SOCKET_DIR }

    private fun bindDestination(entry: String): String? {
        val separator = entry.indexOf(':')
        if (separator < 0 || separator == entry.lastIndex) return null
        return entry.substring(separator + 1).substringBefore(':').trim()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
