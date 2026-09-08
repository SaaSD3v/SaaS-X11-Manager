package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RootStatus { Checking, Granted, Denied }

enum class RootProvider(val displayName: String) {
    KernelSU("KernelSU"),
    APatch("APatch"),
    Magisk("Magisk"),
    SuperSU("SuperSU"),
    LineageSU("LineageSU"),
    Unknown("Unknown")
}

object RootChecker {

    private const val KSU_DIR = "/data/adb/ksu"
    private const val KSU_BIN = "/data/adb/ksud"
    private const val APATCH_DIR = "/data/adb/ap"
    private const val APATCH_BIN = "/data/adb/apd"
    private const val MAGISK_DIR = "/data/adb/magisk"

    suspend fun checkRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            if (Shell.isAppGrantedRoot() == true) {
                if (ShellUtils.fastCmdResult("id")) RootStatus.Granted else RootStatus.Denied
            } else {
                val result = Shell.cmd("id").exec()
                if (result.isSuccess && Shell.isAppGrantedRoot() == true) RootStatus.Granted
                else RootStatus.Denied
            }
        } catch (e: Exception) {
            RootStatus.Denied
        }
    }

    fun checkRootAccessSync(): RootStatus {
        return try {
            if (Shell.isAppGrantedRoot() == true && ShellUtils.fastCmdResult("id")) {
                RootStatus.Granted
            } else {
                RootStatus.Denied
            }
        } catch (e: Exception) {
            RootStatus.Denied
        }
    }

    fun getRootProvider(): String = detectRootProvider().displayName

    /**
     * Provider detection is diagnostic only. Keep it deliberately cheap: the
     * Manager needs a working root shell, not a package-manager inventory.
     *
     * Older code issued multiple `pm path` commands here. Because libsu owns a
     * shared shell, those probes could sit in front of X11/container operations
     * and make unrelated UI actions appear frozen. Root-manager package presence
     * is therefore no longer queried from the interactive runtime path.
     */
    fun detectRootProvider(): RootProvider {
        return try {
            val probe = Shell.cmd(ROOT_PROVIDER_PROBE).exec()
            val output = (probe.out + probe.err).joinToString("\n").lowercase()
            when {
                "kernelsu" in output || "ksud" in output -> RootProvider.KernelSU
                "apatch" in output || "apd" in output -> RootProvider.APatch
                "magisk" in output || "zygisk" in output -> RootProvider.Magisk
                "supersu" in output -> RootProvider.SuperSU
                "lineagesu" in output || "addonsu" in output -> RootProvider.LineageSU
                else -> RootProvider.Unknown
            }
        } catch (e: Exception) {
            RootProvider.Unknown
        }
    }

    private val ROOT_PROVIDER_PROBE = """
        {
          su -v 2>/dev/null
          test -d $KSU_DIR && echo kernelsu
          test -f $KSU_BIN && echo kernelsu
          test -d $APATCH_DIR && echo apatch
          test -f $APATCH_BIN && echo apatch
          test -d $MAGISK_DIR && echo magisk
          command -v magisk >/dev/null 2>&1 && echo magisk
        } 2>/dev/null
    """
}
