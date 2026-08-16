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
        val mountPoint: String? = null
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

    fun open(rootfsPath: String, tag: String = "edit"): Access? {
        if (rootfsPath.isBlank()) return null

        val rootfsArg = shellQuote(rootfsPath)
        if (Shell.cmd("test -d $rootfsArg").exec().isSuccess) {
            return Access(path = rootfsPath)
        }

        val isFile = Shell.cmd("test -f $rootfsArg").exec().isSuccess
        val isBlock = Shell.cmd("test -b $rootfsArg").exec().isSuccess
        if (!isFile && !isBlock) return null

        val mountPoint = newMountPoint(tag)
        val mountPointArg = shellQuote(mountPoint)
        val mkdir = Shell.cmd("mkdir -p $mountPointArg 2>/dev/null").exec()
        if (!mkdir.isSuccess) return null

        val mountCommand = if (isFile) {
            "mount -o loop,rw $rootfsArg $mountPointArg 2>/dev/null"
        } else {
            "mount -o rw $rootfsArg $mountPointArg 2>/dev/null"
        }

        val mounted = Shell.cmd(mountCommand).exec()
        if (!mounted.isSuccess) {
            Shell.cmd("rmdir $mountPointArg 2>/dev/null").exec()
            return null
        }

        return Access(path = mountPoint, mountPoint = mountPoint)
    }

    fun close(access: Access?) {
        val mountPoint = access?.mountPoint ?: return
        val mountPointArg = shellQuote(mountPoint)

        if (Shell.cmd("mountpoint -q $mountPointArg 2>/dev/null").exec().isSuccess) {
            Shell.cmd("umount $mountPointArg 2>/dev/null").exec()
        }
        Shell.cmd("rmdir $mountPointArg 2>/dev/null").exec()
    }

    inline fun <T> use(rootfsPath: String, tag: String = "edit", block: (String) -> T): T? {
        val access = open(rootfsPath, tag) ?: return null
        return try {
            block(access.path)
        } finally {
            close(access)
        }
    }
}
