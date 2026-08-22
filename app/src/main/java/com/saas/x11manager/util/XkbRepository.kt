package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class XkbRootStrategy {
    LIVE_PROC_ROOT,
    DIRECT_DIRECTORY,
    READ_ONLY_MOUNT,
    REFUSE_UNCERTAIN_MOUNT
}

/**
 * Owns the Manager XKB cache independently from the X11 server lifecycle.
 *
 * A running container is read through /proc/<pid>/root, which is the kernel's
 * actual view of that container root and requires no second loop mount. A
 * stopped image/block rootfs is mounted read-only for the short copy. If the
 * runtime state is unknown, image/block roots are not mounted because doing so
 * could race an active DroidSpaces mount.
 */
internal object XkbRepository {

    internal fun selectRootStrategy(
        status: ContainerStatus,
        pid: Int?,
        rootfsIsDirectory: Boolean
    ): XkbRootStrategy = when {
        status == ContainerStatus.RUNNING && pid != null && pid > 0 ->
            XkbRootStrategy.LIVE_PROC_ROOT
        rootfsIsDirectory -> XkbRootStrategy.DIRECT_DIRECTORY
        status == ContainerStatus.STOPPED -> XkbRootStrategy.READ_ONLY_MOUNT
        else -> XkbRootStrategy.REFUSE_UNCERTAIN_MOUNT
    }

    fun hasCachedConfig(): Boolean {
        val root = shellQuote(Constants.INTEGRATED_X11_XKB_DIR)
        return try {
            Shell.cmd(
                "test -d $root/rules && test -d $root/symbols && test -d $root/keycodes"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    suspend fun ensureCached(
        containerName: String,
        logger: ContainerLogger? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (hasCachedConfig()) {
            logger?.i("[+] Reusing cached XKB configuration")
            return@withContext true
        }

        val info = ContainerManager.getContainerInfo(containerName) ?: run {
            logger?.e("[-] Cannot resolve container rootfs for XKB data")
            return@withContext false
        }

        val rootfsIsDirectory = try {
            Shell.cmd("test -d ${shellQuote(info.rootfsPath)}").exec().isSuccess
        } catch (_: Exception) {
            false
        }

        val strategy = selectRootStrategy(
            status = info.status,
            pid = info.pid,
            rootfsIsDirectory = rootfsIsDirectory
        )

        val staged = when (strategy) {
            XkbRootStrategy.LIVE_PROC_ROOT -> {
                val pid = requireNotNull(info.pid)
                val liveRoot = "/proc/$pid/root"
                if (!Shell.cmd("test -d ${shellQuote(liveRoot)}").exec().isSuccess) {
                    logger?.e("[-] Container $containerName PID $pid no longer exposes a live rootfs")
                    false
                } else {
                    logger?.i("[+] Reading XKB from live container root /proc/$pid/root; no extra mount required")
                    stageFromRoot(liveRoot)
                }
            }

            XkbRootStrategy.DIRECT_DIRECTORY -> {
                logger?.i("[+] Reading XKB directly from directory rootfs")
                stageFromRoot(info.rootfsPath)
            }

            XkbRootStrategy.READ_ONLY_MOUNT -> {
                logger?.i("[*] Mounting stopped rootfs read-only for XKB bootstrap...")
                RootfsAccessor.use(
                    rootfsPath = info.rootfsPath,
                    tag = "xkb_$containerName",
                    readOnly = true
                ) { root -> stageFromRoot(root) } ?: false
            }

            XkbRootStrategy.REFUSE_UNCERTAIN_MOUNT -> {
                logger?.e(
                    "[-] Container runtime state is unknown; refusing to mount its image/block rootfs for XKB"
                )
                false
            }
        }

        if (staged && hasCachedConfig()) {
            logger?.i("[+] Cached XKB configuration from $containerName")
            true
        } else {
            logger?.e(
                "[-] XKB configuration was not found in $containerName; expected /usr/share/X11/xkb"
            )
            false
        }
    }

    fun clearCache(): Boolean {
        return try {
            Shell.cmd(
                "rm -rf ${shellQuote(Constants.INTEGRATED_X11_XKB_DIR)} 2>/dev/null"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    private fun stageFromRoot(root: String): Boolean {
        val x11Path = "$root/usr/share/X11/xkb"
        val alternatePath = "$root/usr/share/xkeyboard-config-2"
        val source = when {
            Shell.cmd("test -d ${shellQuote(x11Path)}").exec().isSuccess -> x11Path
            Shell.cmd("test -d ${shellQuote(alternatePath)}").exec().isSuccess -> alternatePath
            else -> null
        } ?: return false

        val destination = Constants.INTEGRATED_X11_XKB_DIR
        val temporary =
            "$destination.tmp.${android.os.Process.myPid()}.${System.nanoTime()}"
        val result = Shell.cmd(
            "rm -rf ${shellQuote(temporary)} && " +
                "mkdir -p ${shellQuote(temporary)} && " +
                "cp -a ${shellQuote("$source/.")} ${shellQuote("$temporary/")} && " +
                "chmod -R a+rX ${shellQuote(temporary)} && " +
                "rm -rf ${shellQuote(destination)} && " +
                "mv ${shellQuote(temporary)} ${shellQuote(destination)}"
        ).exec()
        if (!result.isSuccess) {
            Shell.cmd("rm -rf ${shellQuote(temporary)} 2>/dev/null").exec()
        }
        return result.isSuccess
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
