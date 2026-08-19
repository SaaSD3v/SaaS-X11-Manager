package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedX11RuntimeTest {

    @Test
    fun integratedServerCommandUsesManagerApkRuntimeAndXkbCache() {
        val command = X11SessionManager.buildIntegratedServerCommand(
            "/data/app/~~demo/com.saas.x11manager/base.apk"
        )

        assertTrue(command.contains("CLASSPATH='/data/app/~~demo/com.saas.x11manager/base.apk'"))
        assertTrue(command.contains("TMPDIR='${Constants.INTEGRATED_X11_RUNTIME_DIR}'"))
        assertTrue(command.contains("XKB_CONFIG_ROOT='${Constants.INTEGRATED_X11_XKB_DIR}'"))
        assertTrue(command.contains("--nice-name=${Constants.X11_SERVER_PROCESS}"))
        assertTrue(command.contains("com.termux.x11.CmdEntryPoint ${Constants.X11_DISPLAY}"))
        assertFalse(command.contains("loader.apk"))
        assertFalse(command.contains("am start -n com.termux.x11"))
    }

    @Test
    fun x11SocketAndXkbCacheAreOwnedByManagerRuntime() {
        assertTrue(Constants.X11_SOCK_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertTrue(Constants.X11_SOCK_FILE.endsWith("/.X11-unix/X0"))
        assertTrue(Constants.INTEGRATED_X11_XKB_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertFalse(Constants.X11_SOCK_DIR.contains("/data/data/com.termux"))
        assertFalse(Constants.INTEGRATED_X11_XKB_DIR.contains("/data/data/com.termux"))
    }
}
