package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RootStatus { Checking, Granted, Denied }

object RootChecker {
    suspend fun checkRootAccess(): RootStatus = withContext(Dispatchers.IO) {
        return@withContext try {
            val isRootGranted = Shell.isAppGrantedRoot() == true
            if (isRootGranted) {
                if (ShellUtils.fastCmdResult("id")) RootStatus.Granted
                else RootStatus.Denied
            } else {
                try {
                    val result = Shell.cmd("id").exec()
                    if (result.isSuccess && Shell.isAppGrantedRoot() == true) RootStatus.Granted
                    else RootStatus.Denied
                } catch (e: Exception) {
                    RootStatus.Denied
                }
            }
        } catch (e: Exception) {
            RootStatus.Denied
        }
    }

    fun getRootProvider(): String {
        return try {
            val ks = Shell.cmd("test -d /data/adb/modules/ksu && echo ksu").exec()
            if (ks.isSuccess && ks.out.any { it.contains("ksu") }) return "KernelSU"
            val ap = Shell.cmd("test -d /data/adb/modules/apatch && echo ap").exec()
            if (ap.isSuccess && ap.out.any { it.contains("ap") }) return "APatch"
            val mg = Shell.cmd("test -d /data/adb/modules && ls /data/adb/modules 2>/dev/null | grep -v ksu | grep -v apatch | head -1").exec()
            if (mg.isSuccess && mg.out.isNotEmpty()) return "Magisk"
            "Unknown"
        } catch (_: Exception) { "Unknown" }
    }
}
