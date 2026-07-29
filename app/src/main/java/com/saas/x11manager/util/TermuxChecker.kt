package com.saas.x11manager.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TermuxStatus { Checking, Installed, NotInstalled }
enum class X11ApkStatus { Checking, Installed, NotInstalled }

object TermuxChecker {
    suspend fun checkTermux(): TermuxStatus = withContext(Dispatchers.IO) {
        try {
            var r = Shell.cmd("pm path ${Constants.TERMUX_PACKAGE} 2>/dev/null").exec()
            if (r.isSuccess && r.out.any { it.contains("package:") }) {
                return@withContext TermuxStatus.Installed
            }
            r = Shell.cmd("ls ${Constants.TERMUX_DATA_DIR} 2>/dev/null && echo found").exec()
            if (r.isSuccess && r.out.any { it.contains("found") }) return@withContext TermuxStatus.Installed
            r = Shell.cmd("ls ${Constants.TERMUX_DATA_ALT} 2>/dev/null && echo found").exec()
            if (r.isSuccess && r.out.any { it.contains("found") }) return@withContext TermuxStatus.Installed
            TermuxStatus.NotInstalled
        } catch (_: Exception) { TermuxStatus.NotInstalled }
    }

    suspend fun checkX11Apk(): X11ApkStatus = withContext(Dispatchers.IO) {
        try {
            var r = Shell.cmd("pm path ${Constants.TERMUX_X11_PACKAGE} 2>/dev/null").exec()
            if (r.isSuccess && r.out.any { it.contains("package:") }) {
                return@withContext X11ApkStatus.Installed
            }
            val x11DataDir = "/data/data/${Constants.TERMUX_X11_PACKAGE}"
            val x11DataAlt = "/data/user/0/${Constants.TERMUX_X11_PACKAGE}"
            r = Shell.cmd("ls $x11DataDir 2>/dev/null && echo found").exec()
            if (r.isSuccess && r.out.any { it.contains("found") }) return@withContext X11ApkStatus.Installed
            r = Shell.cmd("ls $x11DataAlt 2>/dev/null && echo found").exec()
            if (r.isSuccess && r.out.any { it.contains("found") }) return@withContext X11ApkStatus.Installed
            X11ApkStatus.NotInstalled
        } catch (_: Exception) { X11ApkStatus.NotInstalled }
    }

    suspend fun checkTermuxX11Loader(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("test -f '${Constants.LOADER_APK}' && echo ok").exec()
                .let { it.isSuccess && it.out.any { o -> o.contains("ok") } }
        } catch (_: Exception) { false }
    }
}
