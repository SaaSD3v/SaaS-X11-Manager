package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Immutable view of the Manager-owned sidecar for one container.
 *
 * Reading one snapshot avoids a privileged shell call for every individual
 * field/session marker while keeping the on-disk format unchanged.
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
    private val settingsWriteLock = Any()
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
     * Related UI/runtime getters normally arrive in a short burst. Reusing the
     * parsed snapshot for one second collapses those calls to one root read.
     *
     * Cache misses are serialized with writes. This prevents a reader from
     * loading old disk contents, racing a completed write/invalidation, then
     * publishing that old snapshot back into the cache after the new file is
     * already visible.
     */
    fun readSnapshot(
        containerName: String,
        forceRefresh: Boolean = false
    ): ContainerSettingsSnapshot {
        if (!forceRefresh) {
            cachedSnapshot(containerName)?.let { return it }
        }

        return synchronized(settingsWriteLock) {
            // Another reader may have populated the cache while this caller was
            // waiting for an in-flight write/read. Recheck before touching disk.
            if (!forceRefresh) {
                cachedSnapshot(containerName)?.let { return@synchronized it }
            }

            val snapshot = parseSnapshot(readLines(containerName))
            synchronized(snapshotCacheLock) {
                snapshotCache[containerName] = CachedSnapshot(
                    loadedAtNanos = System.nanoTime(),
                    snapshot = snapshot
                )
            }
            snapshot
        }
    }

    private fun cachedSnapshot(containerName: String): ContainerSettingsSnapshot? {
        val now = System.nanoTime()
        return synchronized(snapshotCacheLock) {
            val cached = snapshotCache[containerName] ?: return@synchronized null
            if (now - cached.loadedAtNanos <= SNAPSHOT_CACHE_WINDOW_NANOS) {
                cached.snapshot
            } else {
                snapshotCache.remove(containerName)
                null
            }
        }
    }

    /** Keeps the old readValue contract: the first occurrence of a key wins. */
    internal fun parseSnapshot(lines: List<String>): ContainerSettingsSnapshot {
        val values = linkedMapOf<String, String>()
        lines.asSequence()
            .map(String::trim)
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

    /**
     * Serializes Manager read-modify-write updates. Data is first staged in app
     * cache, copied to a temporary file beside the destination, chmod'ed, then
     * atomically renamed over the sidecar. The old file therefore remains valid
     * until the complete replacement is ready.
     */
    private fun setValues(
        containerName: String,
        values: Map<String, String>,
        cacheDir: File
    ): Boolean = synchronized(settingsWriteLock) {
        // Once a writer owns the mutation lock, no new cache-miss reader may
        // touch disk until the transaction finishes. Remove the old fast-path
        // snapshot immediately so readers starting after this point cannot see it.
        invalidateSnapshot(containerName)

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
        val appTemp = File.createTempFile("saas_x11_${safeName}_", ".conf", cacheDir)
        val destinationTemp =
            "$settingsPath.tmp.${android.os.Process.myPid()}.${System.nanoTime()}"

        try {
            appTemp.writeText(lines.joinToString("\n") + "\n")
            val result = Shell.cmd(
                "mkdir -p ${shellQuote(containerDir)} && " +
                    "cp ${shellQuote(appTemp.absolutePath)} ${shellQuote(destinationTemp)} && " +
                    "chmod 600 ${shellQuote(destinationTemp)} && " +
                    "mv -f ${shellQuote(destinationTemp)} ${shellQuote(settingsPath)}"
            ).exec()

            if (result.isSuccess) {
                true
            } else {
                Shell.cmd("rm -f ${shellQuote(destinationTemp)} 2>/dev/null").exec()
                false
            }
        } catch (_: Exception) {
            try {
                Shell.cmd("rm -f ${shellQuote(destinationTemp)} 2>/dev/null").exec()
            } catch (_: Exception) {
            }
            false
        } finally {
            appTemp.delete()
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
