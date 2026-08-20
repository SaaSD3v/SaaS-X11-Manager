package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Immutable view of the Manager-owned sidecar for one container.
 *
 * Reading the sidecar through one snapshot avoids spawning a root shell for
 * every individual field/session marker while keeping the persisted format
 * unchanged.
 */
data class ContainerSettingsSnapshot(
    val initSystem: InitSystem? = null,
    val platform: ContainerPlatform? = null,
    val graphicSession: GraphicSession? = null,
    val installedSessions: Set<GraphicSession> = emptySet()
) {
    fun isGraphicSessionInstalled(graphicSession: GraphicSession): Boolean =
        graphicSession != GraphicSession.NONE && graphicSession in installedSessions
}

/**
 * SaaS-X11-Manager-owned settings stored beside container.config.
 *
 * Keeping app metadata in a sidecar avoids adding private keys to the
 * DroidSpaces config format and makes the settings travel with the container
 * directory. Unknown keys are preserved so this file can grow without
 * rewriting unrelated settings.
 */
object ContainerSettingsManager {

    private const val SETTINGS_FILE = ".saas-x11-manager.conf"
    private const val INIT_SYSTEM_KEY = "init_system"
    private const val PLATFORM_KEY = "platform"
    private const val GRAPHIC_SESSION_KEY = "graphic_session"
    private const val SNAPSHOT_CACHE_WINDOW_NANOS = 1_000_000_000L

    private data class CachedSnapshot(
        val loadedAtNanos: Long,
        val snapshot: ContainerSettingsSnapshot
    )

    private val snapshotCacheLock = Any()
    private val snapshotCache = mutableMapOf<String, CachedSnapshot>()

    fun getInitSystem(containerName: String): InitSystem? =
        readSnapshot(containerName).initSystem

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

    fun getPlatform(containerName: String): ContainerPlatform? =
        readSnapshot(containerName).platform

    fun setPlatform(
        containerName: String,
        platform: ContainerPlatform,
        cacheDir: File
    ): Boolean = setValue(
        containerName = containerName,
        key = PLATFORM_KEY,
        value = when (platform) {
            ContainerPlatform.UBUNTU -> "ubuntu"
            ContainerPlatform.ALPINE -> "alpine"
        },
        cacheDir = cacheDir
    )

    fun getGraphicSession(containerName: String): GraphicSession? =
        readSnapshot(containerName).graphicSession

    fun setGraphicSession(
        containerName: String,
        graphicSession: GraphicSession,
        cacheDir: File
    ): Boolean = setValue(
        containerName = containerName,
        key = GRAPHIC_SESSION_KEY,
        value = graphicSession.name.lowercase(),
        cacheDir = cacheDir
    )

    fun setProfile(
        containerName: String,
        platform: ContainerPlatform,
        initSystem: InitSystem,
        graphicSession: GraphicSession,
        cacheDir: File
    ): Boolean = setValues(
        containerName = containerName,
        values = linkedMapOf(
            PLATFORM_KEY to when (platform) {
                ContainerPlatform.UBUNTU -> "ubuntu"
                ContainerPlatform.ALPINE -> "alpine"
            },
            INIT_SYSTEM_KEY to when (initSystem) {
                InitSystem.SYSTEMD -> "systemd"
                InitSystem.OPENRC -> "openrc"
            },
            GRAPHIC_SESSION_KEY to graphicSession.name.lowercase()
        ),
        cacheDir = cacheDir
    )

    fun isGraphicSessionInstalled(
        containerName: String,
        graphicSession: GraphicSession
    ): Boolean = readSnapshot(containerName).isGraphicSessionInstalled(graphicSession)

    fun setGraphicSessionInstalled(
        containerName: String,
        graphicSession: GraphicSession,
        installed: Boolean,
        cacheDir: File
    ): Boolean {
        if (graphicSession == GraphicSession.NONE) return false
        return setValue(
            containerName = containerName,
            key = installedSessionKey(graphicSession),
            value = if (installed) "1" else "0",
            cacheDir = cacheDir
        )
    }

    /**
     * Reads and parses the sidecar once, then reuses that immutable snapshot for
     * a short burst of related getters. Edit Container typically asks for the
     * selected profile followed by every installed_<session> marker, so this
     * collapses dozens of root `cat` calls into one without making settings
     * sticky for the lifetime of the app.
     *
     * Manager writes invalidate the entry immediately. The one-second lifetime
     * also keeps manual/external edits observable without requiring another
     * privileged stat call before every getter.
     */
    fun readSnapshot(
        containerName: String,
        forceRefresh: Boolean = false
    ): ContainerSettingsSnapshot {
        val now = System.nanoTime()
        if (!forceRefresh) {
            synchronized(snapshotCacheLock) {
                snapshotCache[containerName]?.let { cached ->
                    if (now - cached.loadedAtNanos <= SNAPSHOT_CACHE_WINDOW_NANOS) {
                        return cached.snapshot
                    }
                }
            }
        }

        val snapshot = parseSnapshot(readLines(containerName))
        synchronized(snapshotCacheLock) {
            snapshotCache[containerName] = CachedSnapshot(
                loadedAtNanos = System.nanoTime(),
                snapshot = snapshot
            )
        }
        return snapshot
    }

    internal fun parseSnapshot(lines: List<String>): ContainerSettingsSnapshot {
        val values = linkedMapOf<String, String>()
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEach
                val key = parts[0].trim()
                if (key.isEmpty() || key in values) return@forEach
                values[key] = parts[1].trim()
            }

        val initSystem = when (values[INIT_SYSTEM_KEY]?.lowercase()) {
            "systemd" -> InitSystem.SYSTEMD
            "openrc" -> InitSystem.OPENRC
            else -> null
        }
        val platform = when (values[PLATFORM_KEY]?.lowercase()) {
            "ubuntu" -> ContainerPlatform.UBUNTU
            "alpine" -> ContainerPlatform.ALPINE
            else -> null
        }
        val graphicSession = values[GRAPHIC_SESSION_KEY]?.let { saved ->
            GraphicSession.entries.firstOrNull {
                it.name.equals(saved.trim(), ignoreCase = true)
            }
        }
        val installedSessions = GraphicSession.entries
            .asSequence()
            .filter { it != GraphicSession.NONE }
            .filter { session ->
                when (values[installedSessionKey(session)]?.lowercase()) {
                    "1", "true", "yes" -> true
                    else -> false
                }
            }
            .toSet()

        return ContainerSettingsSnapshot(
            initSystem = initSystem,
            platform = platform,
            graphicSession = graphicSession,
            installedSessions = installedSessions
        )
    }

    private fun installedSessionKey(graphicSession: GraphicSession): String =
        "installed_${graphicSession.name.lowercase()}"

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
    ): Boolean = setValues(
        containerName = containerName,
        values = linkedMapOf(key to value),
        cacheDir = cacheDir
    )

    private fun setValues(
        containerName: String,
        values: Map<String, String>,
        cacheDir: File
    ): Boolean {
        val containerDir = containerDir(containerName)
        val settingsPath = settingsPath(containerName)
        val lines = readLines(containerName).toMutableList()
        val keys = values.keys

        lines.removeAll { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("#") && trimmed.substringBefore("=", "").trim() in keys
        }

        if (lines.none { it.trim().isNotEmpty() }) {
            lines.add("# SaaS-X11-Manager container settings")
        }
        values.forEach { (key, value) -> lines.add("$key=$value") }

        val safeName = containerName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val tmpFile = File.createTempFile("saas_x11_${safeName}_", ".conf", cacheDir)

        return try {
            tmpFile.writeText(lines.joinToString("\n") + "\n")
            val result = Shell.cmd(
                "mkdir -p ${shellQuote(containerDir)} && " +
                    "cp ${shellQuote(tmpFile.absolutePath)} ${shellQuote(settingsPath)} && " +
                    "chmod 600 ${shellQuote(settingsPath)}"
            ).exec()
            if (result.isSuccess) {
                invalidateSnapshot(containerName)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            tmpFile.delete()
        }
    }

    private fun invalidateSnapshot(containerName: String) {
        synchronized(snapshotCacheLock) {
            snapshotCache.remove(containerName)
        }
    }

    private fun containerDir(containerName: String): String =
        "${Constants.CONTAINERS_DIR}/$containerName"

    private fun settingsPath(containerName: String): String =
        "${containerDir(containerName)}/$SETTINGS_FILE"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
