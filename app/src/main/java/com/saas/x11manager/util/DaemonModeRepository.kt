package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell

object DaemonModeRepository {
    fun writeToDisk(enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        Shell.cmd("echo '$value' > '${Constants.DAEMON_MODE_FILE}'").submit()
    }

    fun readFromDisk(): Boolean? {
        val result = Shell.cmd("cat '${Constants.DAEMON_MODE_FILE}' 2>/dev/null").exec()
        return if (result.isSuccess && result.out.isNotEmpty()) result.out[0].trim() == "1" else null
    }
}
