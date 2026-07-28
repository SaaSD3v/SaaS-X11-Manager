package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

object SymlinkInstaller {
    fun isSymlinkEnabled(): Boolean =
        Shell.cmd("test -L '${Constants.SYSTEM_BIN_SYMLINK_PATH}'").exec().isSuccess

    fun enable(): Boolean {
        val binDir = Constants.MODULE_SYSTEM_BIN_PATH
        val link = Constants.SYSTEM_BIN_SYMLINK_PATH
        val target = Constants.DS_BINARY_PATH

        Shell.cmd("mkdir -p '$binDir'").exec().takeIf { it.isSuccess } ?: return false
        Shell.cmd("rm -f '$link'").exec()
        Shell.cmd("ln -sf '$target' '$link'").exec().takeIf { it.isSuccess } ?: return false
        Shell.cmd("chmod 755 '$link'").exec()
        return true
    }

    fun disable(): Boolean =
        Shell.cmd("rm -rf '${Constants.MODULE_SYSTEM_BIN_PATH}'").exec().isSuccess
}
