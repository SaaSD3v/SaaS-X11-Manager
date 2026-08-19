package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the minimum DroidSpaces container.config changes required by the
 * integrated SaaS X11 backend.
 *
 * Existing bind mounts are preserved, while any mount targeting
 * /usr/.X11-unix is replaced by the host socket directory owned by this app.
 */
object ContainerConfigManager {

    private const val X11_CONTAINER_SOCKET_DIR = "/usr/.X11-unix"
    private val x11Bind: String
        get() = "${Constants.X11_SOCK_DIR}:$X11_CONTAINER_SOCKET_DIR"

    suspend fun ensureManualX11Config(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val configPath = "${Constants.CONTAINERS_DIR}/$containerName/${Constants.CONFIG_FILE}"

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
                logger?.i("[+] Integrated X11 container config already ready")
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

            logger?.i("[+] Integrated X11 container config ready")
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
            val raw = updated[index]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains('=')) continue

            val key = trimmed.substringBefore('=').trim()
            when (key) {
                "enable_termux_x11" -> {
                    // DroidSpaces' own Termux:X11 integration stays disabled: this
                    // app provides and owns the X11 socket itself.
                    x11FlagFound = true
                    updated[index] = "enable_termux_x11=0"
                }
                "bind_mounts" -> {
                    bindMountsFound = true
                    val value = trimmed.substringAfter('=', "")
                    val entries = value.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .filterNot { bindDestination(it) == X11_CONTAINER_SOCKET_DIR }
                        .toMutableList()

                    if (x11Bind !in entries) entries.add(x11Bind)
                    updated[index] = "bind_mounts=${entries.distinct().joinToString(",")}"
                }
            }
        }

        if (!x11FlagFound) updated.add("enable_termux_x11=0")
        if (!bindMountsFound) updated.add("bind_mounts=$x11Bind")

        return updated
    }

    private fun bindDestination(entry: String): String? {
        val separator = entry.indexOf(':')
        if (separator < 0 || separator == entry.lastIndex) return null
        return entry.substring(separator + 1).substringBefore(':').trim()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
