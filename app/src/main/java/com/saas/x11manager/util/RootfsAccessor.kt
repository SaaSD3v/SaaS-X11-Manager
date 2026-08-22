package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

/**
 * Gives callers a directory view of a DroidSpaces rootfs regardless of
 * whether rootfs_path points to a directory, an ext4 image, or a block device.
 *
 * Mounted rootfs instances always use an operation-owned mount point so this
 * helper never has to pre-emptively unmount a path that may belong to another
 * process or an earlier manual mount.
 */
object RootfsAccessor {

    data class Access(
        val path: String,
        val mountPoint: String? = null,
        val readOnly: Boolean = false
    ) {
        val isMounted: Boolean get() = mountPoint != null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun newMountPoint(tag: String): String {
        val safeTag = tag.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val suffix = "${android.os.Process.myPid()}_${System.nanoTime().toString(16).replace('-', 'n')}"
        return "/mnt/saas_x11_${safeTag}_$suffix"
    }

    internal fun buildMountCommand(
        rootfsArg: String,
        mountPointArg: String,
        isFile: Boolean,
        readOnly: Boolean
    ): String {
        val access = if (readOnly) "ro" else "rw"
        return if (isFile) {
            "mount -o loop,$access $rootfsArg $mountPointArg 2>/dev/null"
        } else {
            "mount -o $access $rootfsArg $mountPointArg 2>/dev/null"
        }
    }

    fun open(
        rootfsPath: String,
        tag: String = "edit",
        readOnly: Boolean = false
    ): Access? {
        if (rootfsPath.isBlank()) return null

        val rootfsArg = shellQuote(rootfsPath)
        if (Shell.cmd("test -d $rootfsArg").exec().isSuccess) {
            return Access(path = rootfsPath, readOnly = readOnly)
        }

        val isFile = Shell.cmd("test -f $rootfsArg").exec().isSuccess
        val isBlock = Shell.cmd("test -b $rootfsArg").exec().isSuccess
        if (!isFile && !isBlock) return null

        val mountPoint = newMountPoint(tag)
        val mountPointArg = shellQuote(mountPoint)
        val mkdir = Shell.cmd("mkdir -p $mountPointArg 2>/dev/null").exec()
        if (!mkdir.isSuccess) return null

        val mounted = Shell.cmd(
            buildMountCommand(
                rootfsArg = rootfsArg,
                mountPointArg = mountPointArg,
                isFile = isFile,
                readOnly = readOnly
            )
        ).exec()
        if (!mounted.isSuccess) {
            Shell.cmd("rmdir $mountPointArg 2>/dev/null").exec()
            return null
        }

        return Access(path = mountPoint, mountPoint = mountPoint, readOnly = readOnly)
    }

    /**
     * Clean up only mounts created by this accessor. Never use lazy/forced
     * unmount here: a busy mount is diagnostic information and must not be
     * detached behind another operation's back.
     */
    fun close(access: Access?): Boolean {
        val mountPoint = access?.mountPoint ?: return true
        val mountPointArg = shellQuote(mountPoint)

        if (Shell.cmd("mountpoint -q $mountPointArg 2>/dev/null").exec().isSuccess) {
            val unmounted = Shell.cmd("umount $mountPointArg 2>/dev/null").exec()
            if (!unmounted.isSuccess) return false
        }

        val removed = Shell.cmd("rmdir $mountPointArg 2>/dev/null").exec()
        return removed.isSuccess ||
            !Shell.cmd("test -e $mountPointArg").exec().isSuccess
    }

    inline fun <T> use(
        rootfsPath: String,
        tag: String = "edit",
        readOnly: Boolean = false,
        block: (String) -> T
    ): T? {
        val access = open(rootfsPath, tag, readOnly) ?: return null
        return try {
            block(access.path)
        } finally {
            close(access)
        }
    }
}
