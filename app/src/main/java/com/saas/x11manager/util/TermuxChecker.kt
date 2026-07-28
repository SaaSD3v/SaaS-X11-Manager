package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TermuxStatus { Checking, Installed, NotInstalled }
enum class X11ApkStatus { Checking, Installed, NotInstalled }

object TermuxChecker {
    suspend fun checkTermux(): TermuxStatus = withContext(Dispatchers.IO) {
        try {
            val r = Shell.cmd("pm path com.termux 2>/dev/null").exec()
            if (r.isSuccess && r.out.any { it.contains("package:") }) TermuxStatus.Installed
            else TermuxStatus.NotInstalled
        } catch (_: Exception) { TermuxStatus.NotInstalled }
    }

    suspend fun checkX11Apk(): X11ApkStatus = withContext(Dispatchers.IO) {
        try {
            val r = Shell.cmd("pm path com.termux.x11 2>/dev/null").exec()
            if (r.isSuccess && r.out.any { it.contains("package:") }) X11ApkStatus.Installed
            else X11ApkStatus.NotInstalled
        } catch (_: Exception) { X11ApkStatus.NotInstalled }
    }
}
