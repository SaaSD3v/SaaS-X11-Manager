package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedX11RuntimeTest {

    @Test
    fun integratedServerCommandUsesFixedX0Runtime() {
        val command = X11SessionManager.buildIntegratedServerCommand(
            "/data/app/~~demo/com.saas.x11manager/base.apk"
        )

        assertTrue(command.contains("CLASSPATH='/data/app/~~demo/com.saas.x11manager/base.apk'"))
        assertTrue(command.contains("TMPDIR='${Constants.INTEGRATED_X11_RUNTIME_DIR}'"))
        assertTrue(command.contains("XKB_CONFIG_ROOT='${Constants.INTEGRATED_X11_XKB_DIR}'"))
        assertTrue(command.contains("--nice-name=${Constants.X11_SERVER_PROCESS}"))
        assertTrue(command.contains("com.termux.x11.CmdEntryPoint ${Constants.X11_DISPLAY}"))
        assertTrue(command.contains(">'${Constants.X11_LOG_FILE}'"))
    }

    @Test
    fun runtimeExposesTheFixedServerLifecycle() {
        val methodNames = X11SessionManager::class.java.declaredMethods.map { it.name }.toSet()

        assertTrue("getServerStatus" in methodNames)
        assertTrue("getServerPid" in methodNames)
        // Kotlin mangles methods returning Result<T> because Result is an inline class.
        assertTrue(methodNames.any { it == "startIntegratedServer" || it.startsWith("startIntegratedServer-") })
        assertTrue("stopIntegratedServer" in methodNames)
        assertTrue("stopX11Session" in methodNames)
        assertTrue("startX11Session" in methodNames)
    }

    @Test
    fun fixedRuntimeAndXkbRemainManagerOwned() {
        assertTrue(Constants.X11_DISPLAY == ":0")
        assertTrue(Constants.X11_SERVER_PROCESS == "saas-x11")
        assertTrue(Constants.X11_SOCK_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertTrue(Constants.X11_SOCK_FILE.endsWith("/.X11-unix/X0"))
        assertTrue(Constants.INTEGRATED_X11_XKB_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertFalse(Constants.X11_SOCK_DIR.contains("/data/data/com.termux"))
        assertFalse(Constants.INTEGRATED_X11_XKB_DIR.contains("/data/data/com.termux"))
    }
}
