package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the minimum DroidSpaces container.config changes required by the
 * integrated SaaS X11 backend.
 *
 * Existing bind mounts are preserved, while any mount targeting
 * /usr/.X11-unix is replaced by the host socket directory for the active
 * Manager display slot.
 */
object ContainerConfigManager {

    private const val X11_CONTAINER_SOCKET_DIR = "/usr/.X11-unix"
    private val displaySocketDirPattern = Regex(
        "^${Regex.escape(Constants.INTEGRATED_X11_RUNTIME_DIR)}/display-(\\d+)/\\.X11-unix$"
    )

    private fun x11Bind(displaySlot: X11DisplaySlot): String =
        "${displaySlot.socketDir}:$X11_CONTAINER_SOCKET_DIR"

    suspend fun ensureManualX11Config(
        containerName: String,
        logger: ContainerLogger? = null,
        displaySlot: X11DisplaySlot = X11DisplaySlot(0)
    ): Boolean = withContext(Dispatchers.IO) {
        val configPath = "${Constants.CONTAINERS_DIR}/$containerName/${Constants.CONFIG_FILE}"
        val requiredBind = x11Bind(displaySlot)

        logger?.i("--- X11 Container Configuration ---")
        logger?.i("[CTX] Container: $containerName")
        logger?.i("[CTX] Config: $configPath")
        logger?.i("[CTX] Requested monitor: ${displaySlot.monitorNumber}")
        logger?.i("[CTX] Requested display: ${displaySlot.displayName}")
        logger?.i("[CTX] Host socket directory: ${displaySlot.socketDir}")
        logger?.i("[CTX] Container socket directory: $X11_CONTAINER_SOCKET_DIR")
        logger?.i("[CTX] Required bind: $requiredBind")
        logger?.i("[CTX] DroidSpaces Termux:X11 integration: disabled (Manager owns X11)")

        try {
            logger?.i("[*] Reading existing container configuration...")
            val read = Shell.cmd("cat ${shellQuote(configPath)} 2>/dev/null").exec()
            if (!read.isSuccess || read.out.isEmpty()) {
                logger?.e("[-] Cannot read container config")
                logger?.i("[CTX] Read exit code: ${read.code}")
                return@withContext false
            }

            val original = read.out.toList()
            val existingBindMounts = original
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .firstOrNull { it.substringBefore('=').trim() == "bind_mounts" }
                ?.substringAfter('=', "")
                .orEmpty()
            val existingSlot = displaySlotFromBindMounts(existingBindMounts)

            logger?.i("[+] Container config read (${original.size} lines)")
            logger?.i(
                "[CTX] Existing Manager X11 bind: " +
                    (existingSlot?.describe() ?: "none")
            )

            val updated = buildManualX11Config(original, displaySlot)
            val originalText = original.joinToString("\n") + "\n"
            val updatedText = updated.joinToString("\n") + "\n"
            if (updatedText == originalText) {
                logger?.i("[CTX] Configuration change required: no")
                logger?.i("[+] Integrated X11 container config already ready for ${displaySlot.describe()}")
                return@withContext true
            }

            logger?.i("[CTX] Configuration change required: yes")
            if (existingSlot != null && existingSlot.number != displaySlot.number) {
                logger?.i(
                    "[CTX] Rebinding Manager X11: ${existingSlot.displayName} -> ${displaySlot.displayName}"
                )
            }

            val tempPath = "$configPath.saas-x11.tmp.${System.nanoTime()}"
            logger?.i("[*] Writing updated configuration atomically...")
            logger?.i("[CTX] Temporary config: $tempPath")
            val write = Shell.cmd(
                "printf '%s' ${shellQuote(updatedText)} > ${shellQuote(tempPath)} && " +
                    "chmod 644 ${shellQuote(tempPath)} && " +
                    "mv -f ${shellQuote(tempPath)} ${shellQuote(configPath)}"
            ).exec()

            if (!write.isSuccess) {
                Shell.cmd("rm -f ${shellQuote(tempPath)} 2>/dev/null").exec()
                logger?.e("[-] Failed to update integrated X11 container config")
                logger?.i("[CTX] Write exit code: ${write.code}")
                logger?.i("[CTX] Temporary config cleanup requested")
                return@withContext false
            }

            logger?.i("[+] Atomic container config update complete")
            logger?.i("[CTX] Active Manager bind: $requiredBind")
            logger?.i("[CTX] enable_termux_x11: 0")
            logger?.i("[+] Integrated X11 container config ready for ${displaySlot.describe()}")
            true
        } catch (e: Exception) {
            logger?.e("[-] X11 config error: ${e.message}")
            logger?.i("[CTX] Container: $containerName")
            logger?.i("[CTX] Config: $configPath")
            false
        }
    }

    internal fun buildManualX11Config(
        original: List<String>,
        displaySlot: X11DisplaySlot = X11DisplaySlot(0)
    ): List<String> {
        val updated = original.toMutableList()
        val requiredBind = x11Bind(displaySlot)
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

                    if (requiredBind !in entries) entries.add(requiredBind)
                    updated[index] = "bind_mounts=${entries.distinct().joinToString(",")}"
                }
            }
        }

        if (!x11FlagFound) updated.add("enable_termux_x11=0")
        if (!bindMountsFound) updated.add("bind_mounts=$requiredBind")

        return updated
    }

    /**
     * Resolves a Manager display slot from the X11 bind currently stored in a
     * container config. This is runtime lease discovery, not a reservation:
     * callers should only treat it as occupied while the container is active.
     */
    internal fun displaySlotFromBindMounts(bindMounts: String): X11DisplaySlot? {
        return bindMounts
            .split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { entry ->
                if (bindDestination(entry) != X11_CONTAINER_SOCKET_DIR) {
                    return@firstNotNullOfOrNull null
                }

                val source = entry.substringBefore(':').trim()
                val displayNumber = displaySocketDirPattern
                    .matchEntire(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@firstNotNullOfOrNull null

                X11DisplaySlot(displayNumber)
            }
    }

    private fun bindDestination(entry: String): String? {
        val separator = entry.indexOf(':')
        if (separator < 0 || separator == entry.lastIndex) return null
        return entry.substring(separator + 1).substringBefore(':').trim()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
