package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DroidspacesChecker {
    suspend fun checkBackend(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("test -x '${Constants.DS_BINARY_PATH}' && echo ok").exec()
                .let { it.isSuccess && it.out.any { o -> o.contains("ok") } }
        } catch (_: Exception) { false }
    }

    fun getBinaryPath(): String = Constants.DS_BINARY_PATH
}
