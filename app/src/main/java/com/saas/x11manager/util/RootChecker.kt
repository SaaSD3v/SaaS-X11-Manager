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
    private const val KSU_PKG = "me.weishu.kernelsu"
    private const val APATCH_DIR = "/data/adb/ap"
    private const val APATCH_BIN = "/data/adb/apd"
    private const val APATCH_PKG = "me.bmax.apatch"
    private const val MAGISK_DIR = "/data/adb/magisk"
    private const val MAGISK_PKG = "com.topjohnwu.magisk"
    private const val SUPERSU_PKG_A = "eu.chainfire.supersu"
    private const val SUPERSU_PKG_B = "com.noshufou.android.su"
    private const val LINEAGE_PKG = "org.lineageos.su"

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

    private val ROOT_PROVIDER_PROBE = """
        {
          su -v 2>/dev/null
          su -V 2>/dev/null
          magisk -V 2>/dev/null && echo magisk
          magisk --path 2>/dev/null
          test -d $KSU_DIR && echo kernelsu
          test -d $APATCH_DIR && echo apatch
          test -d $MAGISK_DIR && echo magisk
          test -f $KSU_BIN && echo kernelsu
          test -f $APATCH_BIN && echo apatch
          pm path $KSU_PKG 2>/dev/null && echo kernelsu
          pm path $APATCH_PKG 2>/dev/null && echo apatch
          pm path $MAGISK_PKG 2>/dev/null && echo magisk
          pm path $SUPERSU_PKG_A 2>/dev/null && echo supersu
          pm path $SUPERSU_PKG_B 2>/dev/null && echo supersu
          pm path $LINEAGE_PKG 2>/dev/null && echo lineagesu
        } 2>/dev/null
    """
}
