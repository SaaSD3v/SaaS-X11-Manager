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
    /**
     * Droidspaces-style root check: prefer libsu's cached grant state, then
     * request/verify a real root shell with a minimal `id` command.
     */
    suspend fun checkRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            // Fast path: check if root is already granted (cached check, no allocation)
            val isRootGranted = Shell.isAppGrantedRoot() == true

            if (isRootGranted) {
                // Use fastCmdResult for verification (minimal overhead)
                if (ShellUtils.fastCmdResult("id")) {
                    RootStatus.Granted
                } else {
                    RootStatus.Denied
                }
            } else {
                // Try to request root (will show dialog)
                try {
                    val result = Shell.cmd("id").exec()
                    if (result.isSuccess && Shell.isAppGrantedRoot() == true) {
                        RootStatus.Granted
                    } else {
                        RootStatus.Denied
                    }
                } catch (e: Exception) {
                    RootStatus.Denied
                }
            }
        } catch (e: Exception) {
            RootStatus.Denied
        }
    }

    fun checkRootAccessSync(): RootStatus {
        return try {
            val isRootAvailable = Shell.isAppGrantedRoot() == true
            if (isRootAvailable) {
                val verifyResult = ShellUtils.fastCmdResult("id")
                if (verifyResult) {
                    RootStatus.Granted
                } else {
                    RootStatus.Denied
                }
            } else {
                RootStatus.Denied
            }
        } catch (e: Exception) {
            RootStatus.Denied
        }
    }

    fun getRootProvider(): String = detectRootProvider().displayName

    fun detectRootProvider(): RootProvider {
        return try {
            val probe = Shell.cmd(ROOT_PROVIDER_PROBE).exec()
            val output = (probe.out + probe.err).joinToString("\n").lowercase()
            when {
                "kernelsu" in output || "ksu" in output -> RootProvider.KernelSU
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

    private const val ROOT_PROVIDER_PROBE = """
        {
          su -v 2>/dev/null
          su -V 2>/dev/null
          magisk -V 2>/dev/null && echo magisk
          magisk --path 2>/dev/null
          test -d /data/adb/ksu && echo kernelsu
          test -d /data/adb/ap && echo apatch
          test -d /data/adb/magisk && echo magisk
          test -f /data/adb/ksud && echo kernelsu
          test -f /data/adb/apd && echo apatch
          pm path me.weishu.kernelsu 2>/dev/null && echo kernelsu
          pm path me.bmax.apatch 2>/dev/null && echo apatch
          pm path com.topjohnwu.magisk 2>/dev/null && echo magisk
          pm path eu.chainfire.supersu 2>/dev/null && echo supersu
          pm path com.noshufou.android.su 2>/dev/null && echo supersu
          pm path org.lineageos.su 2>/dev/null && echo lineagesu
        } 2>/dev/null
    """
}
