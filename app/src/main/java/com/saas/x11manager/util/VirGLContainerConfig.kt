package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns only the DroidSpaces config fields needed by the Manager VirGL bridge.
 *
 * The host runtime directory is stable and is mounted at a stable /usr anchor,
 * so a restarted renderer can recreate .virgl_test without restarting Linux.
 * A foreign bind using the same guest destination is never replaced.
 */
internal object VirGLContainerConfig {
    const val HOST_RUNTIME_DIR =
        "/data/data/com.termux/files/home/.saas-x11-manager/virgl/runtime"
    const val HOST_SOCKET = "$HOST_RUNTIME_DIR/.virgl_test"
    const val GUEST_RUNTIME_DIR = "/usr/.saas-virgl"
    const val GUEST_SOCKET = "$GUEST_RUNTIME_DIR/.virgl_test"

    private const val VIRGL_KEY = "enable_virgl"
    private const val BINDS_KEY = "bind_mounts"

    val requiredBind: String
        get() = "$HOST_RUNTIME_DIR:$GUEST_RUNTIME_DIR"

    enum class NativeState { ABSENT, ON, OFF, UNKNOWN }

    internal data class Analysis(
        val nativeState: NativeState,
        val hasManagedBind: Boolean,
        val conflictingGuestBind: Boolean
    )

    fun analyze(lines: List<String>): Analysis {
        var rawVirgl: String? = null
        var bindValue = ""

        lines.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .forEach { line ->
                when (line.substringBefore('=').trim()) {
                    VIRGL_KEY -> rawVirgl = line.substringAfter('=', "").trim()
                    BINDS_KEY -> bindValue = line.substringAfter('=', "")
                }
            }

        val native = when (rawVirgl?.lowercase()) {
            null, "" -> NativeState.ABSENT
            "1", "true", "yes", "on" -> NativeState.ON
            else -> NativeState.OFF
        }

        var managed = false
        var conflict = false
        bindValue.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { entry ->
                if (bindDestination(entry) != GUEST_RUNTIME_DIR) return@forEach
                if (bindSource(entry) == HOST_RUNTIME_DIR) managed = true
                else conflict = true
            }

        return Analysis(native, managed, conflict)
    }

    fun buildAppliedConfig(original: List<String>): List<String>? {
        val analysis = analyze(original)
        if (analysis.conflictingGuestBind) return null

        val updated = original.toMutableList()
        var virglFound = false
        var bindsFound = false

        for (index in updated.indices) {
            val trimmed = updated[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains('=')) continue

            when (trimmed.substringBefore('=').trim()) {
                VIRGL_KEY -> {
                    if (!virglFound) {
                        updated[index] = "$VIRGL_KEY=0"
                        virglFound = true
                    } else {
                        updated[index] = "# SaaS removed duplicate $VIRGL_KEY: $trimmed"
                    }
                }
                BINDS_KEY -> {
                    if (bindsFound) continue
                    bindsFound = true
                    val entries = trimmed.substringAfter('=', "")
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .filterNot(::isManagedBind)
                        .toMutableList()
                    entries += requiredBind
                    updated[index] = "$BINDS_KEY=${entries.distinct().joinToString(",")}"
                }
            }
        }

        if (!virglFound) updated += "$VIRGL_KEY=0"
        if (!bindsFound) updated += "$BINDS_KEY=$requiredBind"
        return updated
    }

    fun buildRestoredConfig(
        original: List<String>,
        originalNativeState: NativeState
    ): List<String> {
        val updated = original.toMutableList()
        var virglFound = false

        for (index in updated.indices) {
            val trimmed = updated[index].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains('=')) continue

            when (trimmed.substringBefore('=').trim()) {
                VIRGL_KEY -> {
                    when {
                        originalNativeState == NativeState.ABSENT -> updated[index] = ""
                        !virglFound -> {
                            updated[index] = "$VIRGL_KEY=" +
                                if (originalNativeState == NativeState.ON) "1" else "0"
                            virglFound = true
                        }
                        else -> updated[index] = ""
                    }
                }
                BINDS_KEY -> {
                    val entries = trimmed.substringAfter('=', "")
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .filterNot(::isManagedBind)
                        .distinct()
                    updated[index] = "$BINDS_KEY=${entries.joinToString(",")}"
                }
            }
        }

        if (originalNativeState != NativeState.ABSENT && !virglFound) {
            updated += "$VIRGL_KEY=" + if (originalNativeState == NativeState.ON) "1" else "0"
        }
        return updated.filter { it.isNotEmpty() }
    }

    suspend fun apply(
        info: ContainerInfo,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (info.isRunning) {
            logger?.w("[!] VirGL bridge config cannot be changed while ${info.name} is running")
            return@withContext false
        }
        mutate(info.configPath, logger) { buildAppliedConfig(it) }
    }

    suspend fun restore(
        info: ContainerInfo,
        originalNativeState: NativeState,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (info.isRunning) {
            logger?.w("[!] VirGL bridge config will be restored after ${info.name} is stopped")
            return@withContext false
        }
        mutate(info.configPath, logger) {
            buildRestoredConfig(it, originalNativeState)
        }
    }

    private fun mutate(
        configPath: String,
        logger: ContainerLogger?,
        transform: (List<String>) -> List<String>?
    ): Boolean {
        return try {
            val read = Shell.cmd("cat ${q(configPath)} 2>/dev/null").exec()
            if (!read.isSuccess || read.out.isEmpty()) return false

            val original = read.out.toList()
            val updated = transform(original) ?: run {
                logger?.e("[-] A foreign bind already owns $GUEST_RUNTIME_DIR")
                return false
            }
            val originalText = original.joinToString("\n") + "\n"
            val updatedText = updated.joinToString("\n") + "\n"
            if (originalText == updatedText) return true

            val temp = "$configPath.saas-virgl.${android.os.Process.myPid()}"
            val result = Shell.cmd(
                "printf '%s' ${q(updatedText)} > ${q(temp)} && " +
                    "chmod $(stat -c '%a' ${q(configPath)} 2>/dev/null || printf '600') ${q(temp)} 2>/dev/null || true; " +
                    "mv -f ${q(temp)} ${q(configPath)}"
            ).exec()
            if (!result.isSuccess) {
                Shell.cmd("rm -f ${q(temp)} 2>/dev/null || true").exec()
            }
            result.isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun isManagedBind(entry: String): Boolean =
        bindSource(entry) == HOST_RUNTIME_DIR && bindDestination(entry) == GUEST_RUNTIME_DIR

    private fun bindSource(entry: String): String? {
        val separator = entry.indexOf(':')
        if (separator <= 0) return null
        return entry.substring(0, separator).trim().takeIf(String::isNotEmpty)
    }

    private fun bindDestination(entry: String): String? {
        val separator = entry.indexOf(':')
        if (separator < 0 || separator == entry.lastIndex) return null
        return entry.substring(separator + 1).substringBefore(':').trim()
    }

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
