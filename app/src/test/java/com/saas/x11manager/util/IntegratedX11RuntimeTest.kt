package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedX11RuntimeTest {

    @Test
    fun integratedServerCommandUsesOnlyFlatX0Runtime() {
        val command = X11SessionManager.buildIntegratedServerCommand(
            "/data/app/~~demo/com.saas.x11manager/base.apk"
        )

        assertTrue(command.contains("CLASSPATH='/data/app/~~demo/com.saas.x11manager/base.apk'"))
        assertTrue(command.contains("TMPDIR='${Constants.INTEGRATED_X11_RUNTIME_DIR}'"))
        assertTrue(command.contains("XKB_CONFIG_ROOT='${Constants.INTEGRATED_X11_XKB_DIR}'"))
        assertTrue(command.contains("--nice-name=${Constants.X11_SERVER_PROCESS}"))
        assertTrue(command.contains("com.termux.x11.CmdEntryPoint :0"))
        assertTrue(command.contains(">'${Constants.X11_LOG_FILE}'"))
        assertFalse(command.contains("display-"))
        assertFalse(command.contains("saas-x11-0"))
        assertFalse(command.contains(" :1"))
    }

    @Test
    fun runtimeExposesSingleServerLifecycleOnly() {
        val methodNames = X11SessionManager::class.java.declaredMethods.map { it.name }

        assertTrue(methodNames.contains("getServerStatus"))
        assertTrue(methodNames.contains("getServerPid"))
        assertTrue(methodNames.contains("startIntegratedServer"))
        assertTrue(methodNames.contains("stopIntegratedServer"))
        assertTrue(methodNames.contains("stopX11Session"))
        assertFalse(methodNames.contains("getMonitors"))
        assertFalse(methodNames.contains("getDisplayForContainer"))
        assertFalse(methodNames.contains("selectDisplaySlot"))
        assertFalse(methodNames.contains("openIntegratedDisplay"))
    }

    @Test
    fun fixedRuntimeAndXkbRemainManagerOwned() {
        assertTrue(Constants.X11_SOCK_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertTrue(Constants.X11_SOCK_FILE.endsWith("/.X11-unix/X0"))
        assertTrue(Constants.INTEGRATED_X11_XKB_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertFalse(Constants.X11_SOCK_DIR.contains("/data/data/com.termux"))
        assertFalse(Constants.INTEGRATED_X11_XKB_DIR.contains("/data/data/com.termux"))
    }
}
