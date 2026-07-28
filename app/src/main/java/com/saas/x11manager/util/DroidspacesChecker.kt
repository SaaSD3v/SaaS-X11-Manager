package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class DroidspacesBackendStatus {
    object Checking : DroidspacesBackendStatus()
    object Available : DroidspacesBackendStatus()
    object NotInstalled : DroidspacesBackendStatus()
    object Corrupted : DroidspacesBackendStatus()
    object ModuleMissing : DroidspacesBackendStatus()
}

object DroidspacesChecker {
    private const val MODULE_PROP_PATH = "${Constants.MAGISK_MODULE_PATH}/module.prop"

    suspend fun checkBackend(): Boolean = checkBackendStatus() is DroidspacesBackendStatus.Available

    suspend fun checkBackendStatus(): DroidspacesBackendStatus = withContext(Dispatchers.IO) {
        try {
            if (!checkModuleInstalled()) {
                return@withContext DroidspacesBackendStatus.ModuleMissing
            }

            val droidspacesOk = checkExecutable(Constants.DS_BINARY_PATH)
            val busyboxOk = checkExecutable(Constants.BUSYBOX_BINARY_PATH)

            when {
                droidspacesOk && busyboxOk -> DroidspacesBackendStatus.Available
                !droidspacesOk || !busyboxOk -> DroidspacesBackendStatus.NotInstalled
                else -> DroidspacesBackendStatus.Corrupted
            }
        } catch (e: Exception) {
            DroidspacesBackendStatus.NotInstalled
        }
    }

    suspend fun getDroidspacesVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("${Constants.DS_BINARY_PATH} version 2>&1").exec()
            if (result.isSuccess && result.out.isNotEmpty()) result.out[0].trim() else null
        } catch (e: Exception) {
            null
        }
    }

    fun getBinaryPath(): String = Constants.DS_BINARY_PATH

    private suspend fun checkModuleInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("test -d '${Constants.MAGISK_MODULE_PATH}'").exec().isSuccess &&
                Shell.cmd("test -f '$MODULE_PROP_PATH'").exec().isSuccess
        } catch (e: Exception) {
            false
        }
    }

    private fun checkExecutable(binaryPath: String): Boolean {
        val existsCheck = Shell.cmd("test -f '$binaryPath'").exec()
        if (!existsCheck.isSuccess) return false

        val execCheck = Shell.cmd("test -x '$binaryPath'").exec()
        return execCheck.isSuccess
    }
}
