package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

/**
 * Gives callers a directory view of a DroidSpaces rootfs regardless of
 * whether rootfs_path points to a directory, an ext4 image, or a block device.
 */
object RootfsAccessor {

    data class Access(
        val path: String,
        val mountPoint: String? = null
    ) {
        val isMounted: Boolean get() = mountPoint != null
    }

    fun open(rootfsPath: String, tag: String = "edit"): Access? {
        if (rootfsPath.isBlank()) return null

        if (Shell.cmd("test -d '$rootfsPath'").exec().isSuccess) {
            return Access(path = rootfsPath)
        }

        val isFile = Shell.cmd("test -f '$rootfsPath'").exec().isSuccess
        val isBlock = Shell.cmd("test -b '$rootfsPath'").exec().isSuccess
        if (!isFile && !isBlock) return null

        val safeTag = tag.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val mountPoint = "/mnt/saas_x11_$safeTag"

        Shell.cmd("umount '$mountPoint' 2>/dev/null; mkdir -p '$mountPoint'").exec()

        val mountCommand = if (isFile) {
            "mount -o loop,rw '$rootfsPath' '$mountPoint' 2>/dev/null"
        } else {
            "mount -o rw '$rootfsPath' '$mountPoint' 2>/dev/null"
        }

        val mounted = Shell.cmd(mountCommand).exec()
        if (!mounted.isSuccess) {
            Shell.cmd("rmdir '$mountPoint' 2>/dev/null").exec()
            return null
        }

        return Access(path = mountPoint, mountPoint = mountPoint)
    }

    fun close(access: Access?) {
        val mountPoint = access?.mountPoint ?: return
        Shell.cmd("umount '$mountPoint' 2>/dev/null; rmdir '$mountPoint' 2>/dev/null").exec()
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
