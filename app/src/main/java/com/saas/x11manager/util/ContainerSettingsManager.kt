package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * SaaS-X11-Manager-owned settings stored beside container.config.
 *
 * Keeping app metadata in a sidecar avoids adding private keys to the
 * DroidSpaces config format and makes the settings travel with the container
 * directory. Unknown keys are preserved so this file can grow with future
 * settings such as graphic_session.
 */
object ContainerSettingsManager {

    private const val SETTINGS_FILE = ".saas-x11-manager.conf"
    private const val INIT_SYSTEM_KEY = "init_system"

    fun getInitSystem(containerName: String): InitSystem? {
        return when (readValue(containerName, INIT_SYSTEM_KEY)?.lowercase()) {
            "systemd" -> InitSystem.SYSTEMD
            "openrc" -> InitSystem.OPENRC
            else -> null
        }
    }

    fun setInitSystem(
        containerName: String,
        initSystem: InitSystem,
        cacheDir: File
    ): Boolean {
        val value = when (initSystem) {
            InitSystem.SYSTEMD -> "systemd"
            InitSystem.OPENRC -> "openrc"
        }
        return setValue(containerName, INIT_SYSTEM_KEY, value, cacheDir)
    }

    private fun readValue(containerName: String, key: String): String? {
        return readLines(containerName)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].trim() == key) parts[1].trim() else null
            }
            .firstOrNull()
    }

    private fun readLines(containerName: String): List<String> {
        return try {
            val result = Shell.cmd("cat ${shellQuote(settingsPath(containerName))} 2>/dev/null").exec()
            if (result.isSuccess) result.out else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun setValue(
        containerName: String,
        key: String,
        value: String,
        cacheDir: File
    ): Boolean {
        val containerDir = containerDir(containerName)
        val settingsPath = settingsPath(containerName)
        val lines = readLines(containerName).toMutableList()

        lines.removeAll { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("#") && trimmed.substringBefore("=", "").trim() == key
        }

        if (lines.none { it.trim().isNotEmpty() }) {
            lines.add("# SaaS-X11-Manager container settings")
        }
        lines.add("$key=$value")

        val safeName = containerName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val tmpFile = File.createTempFile("saas_x11_${safeName}_", ".conf", cacheDir)

        return try {
            tmpFile.writeText(lines.joinToString("\n") + "\n")
            val result = Shell.cmd(
                "mkdir -p ${shellQuote(containerDir)} && " +
                    "cp ${shellQuote(tmpFile.absolutePath)} ${shellQuote(settingsPath)} && " +
                    "chmod 600 ${shellQuote(settingsPath)}"
            ).exec()
            result.isSuccess
        } catch (_: Exception) {
            false
        } finally {
            tmpFile.delete()
        }
    }

    private fun containerDir(containerName: String): String =
        "${Constants.CONTAINERS_DIR}/$containerName"

    private fun settingsPath(containerName: String): String =
        "${containerDir(containerName)}/$SETTINGS_FILE"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
