package com.saas.x11manager.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModuleInstallationStep {
    data class RemovingOldModule(val path: String) : ModuleInstallationStep()
    data class ExtractingAssets(val path: String) : ModuleInstallationStep()
    data class CopyingModule(val path: String) : ModuleInstallationStep()
    data class SettingPermissions(val path: String) : ModuleInstallationStep()
    data class Verifying(val path: String) : ModuleInstallationStep()
    object Success : ModuleInstallationStep()
    data class Error(val message: String) : ModuleInstallationStep()
}

object ModuleInstaller {
    private const val MODULE_PATH = Constants.MAGISK_MODULE_PATH
    private const val MODULE_PROP_PATH = "$MODULE_PATH/module.prop"

    suspend fun install(
        context: Context,
        onProgress: (ModuleInstallationStep) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(context.cacheDir, "boot-module-temp")
            val assetManager = context.assets

            val wasSymlinkEnabled = SymlinkInstaller.isSymlinkEnabled()

            onProgress(ModuleInstallationStep.RemovingOldModule(MODULE_PATH))
            Shell.cmd("rm -rf '$MODULE_PATH' 2>&1").exec()

            onProgress(ModuleInstallationStep.ExtractingAssets(tempDir.absolutePath))
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            val assetFiles = assetManager.list("boot-module") ?: emptyArray()
            for (fileName in assetFiles) {
                val assetPath = "boot-module/$fileName"
                val target = File(tempDir, fileName)
                assetManager.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }

            onProgress(ModuleInstallationStep.CopyingModule(MODULE_PATH))
            val mkdirResult = Shell.cmd("mkdir -p '$MODULE_PATH' 2>&1").exec()
            if (!mkdirResult.isSuccess) {
                tempDir.deleteRecursively()
                return@withContext Result.failure(Exception("Failed to create module directory: ${mkdirResult.err.joinToString()}"))
            }

            val copyResult = Shell.cmd("cp -arf '${tempDir.absolutePath}'/* '$MODULE_PATH/' 2>&1").exec()
            tempDir.deleteRecursively()
            if (!copyResult.isSuccess) {
                return@withContext Result.failure(Exception("Failed to copy module files: ${copyResult.err.joinToString()}"))
            }

            onProgress(ModuleInstallationStep.SettingPermissions(MODULE_PATH))
            val chmodResult = Shell.cmd("chmod 755 '$MODULE_PATH'/*.sh 2>&1 && chmod 644 '$MODULE_PATH'/*.prop '$MODULE_PATH'/sepolicy.rule 2>&1").exec()
            if (!chmodResult.isSuccess) {
                return@withContext Result.failure(Exception("Failed to set permissions: ${chmodResult.err.joinToString()}"))
            }

            onProgress(ModuleInstallationStep.Verifying(MODULE_PATH))
            val verifyResult = Shell.cmd("test -d '$MODULE_PATH' && test -f '$MODULE_PROP_PATH' && test -f '$MODULE_PATH/sepolicy.rule' 2>&1").exec()
            if (!verifyResult.isSuccess) {
                return@withContext Result.failure(Exception("Module verification failed: ${verifyResult.err.joinToString()}"))
            }

            if (wasSymlinkEnabled) {
                SymlinkInstaller.enable()
            }

            onProgress(ModuleInstallationStep.Success)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
