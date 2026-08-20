package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedX11RuntimeTest {

    @Test
    fun integratedServerCommandUsesIsolatedMonitorRuntimeAndSharedXkbCache() {
        val slot = X11DisplaySlot(3)
        val command = X11SessionManager.buildIntegratedServerCommand(
            "/data/app/~~demo/com.saas.x11manager/base.apk",
            slot
        )

        assertTrue(command.contains("CLASSPATH='/data/app/~~demo/com.saas.x11manager/base.apk'"))
        assertTrue(command.contains("TMPDIR='${slot.runtimeDir}'"))
        assertTrue(command.contains("XKB_CONFIG_ROOT='${Constants.INTEGRATED_X11_XKB_DIR}'"))
        assertTrue(command.contains("--nice-name=${slot.processName}"))
        assertTrue(command.contains("com.termux.x11.CmdEntryPoint ${slot.displayName}"))
        assertTrue(command.contains(">${slot.logFile}"))
        assertFalse(command.contains("loader.apk"))
        assertFalse(command.contains("am start -n com.termux.x11"))
    }

    @Test
    fun defaultIntegratedServerCommandStillTargetsMonitorOne() {
        val command = X11SessionManager.buildIntegratedServerCommand(
            "/data/app/~~demo/com.saas.x11manager/base.apk"
        )
        val slot = X11DisplaySlot(0)

        assertTrue(command.contains("TMPDIR='${slot.runtimeDir}'"))
        assertTrue(command.contains("--nice-name=${slot.processName}"))
        assertTrue(command.contains("com.termux.x11.CmdEntryPoint :0"))
    }

    @Test
    fun integratedRuntimeDoesNotExposeStandaloneDisplayLaunch() {
        val methodNames = X11SessionManager::class.java.declaredMethods.map { it.name }
        assertFalse(methodNames.contains("openIntegratedDisplay"))
    }

    @Test
    fun integratedRuntimeExposesMonitorAwareLifecycle() {
        val methodNames = X11SessionManager::class.java.declaredMethods.map { it.name }

        assertTrue(methodNames.contains("getServerStatus"))
        assertTrue(methodNames.contains("getServerPid"))
        assertTrue(methodNames.contains("getMonitors"))
        assertTrue(methodNames.contains("getDisplayForContainer"))
        assertTrue(methodNames.contains("stopX11Session"))
        assertFalse(methodNames.contains("getLoaderStatus"))
        assertFalse(methodNames.contains("getLoaderPid"))
        assertFalse(methodNames.contains("startLoader"))
        assertFalse(methodNames.contains("stopLoader"))
        assertTrue(X11ServerStatus.entries.containsAll(listOf(X11ServerStatus.Running, X11ServerStatus.Stopped)))
    }

    @Test
    fun displaySelectionUsesLowestNumberNotOwnedByRunningContainers() {
        assertEquals(0, X11SessionManager.selectDisplaySlot(emptyList()).number)
        assertEquals(2, X11SessionManager.selectDisplaySlot(listOf(0, 1, 4)).number)
        assertEquals(1, X11SessionManager.selectDisplaySlot(listOf(0, 2, 3, 4)).number)
    }

    @Test
    fun monitorRuntimeAndXkbCacheRemainManagerOwned() {
        val slot = X11DisplaySlot(5)

        assertTrue(slot.socketDir.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertTrue(slot.socketFile.endsWith("/.X11-unix/X5"))
        assertTrue(Constants.INTEGRATED_X11_XKB_DIR.startsWith(Constants.INTEGRATED_X11_RUNTIME_DIR))
        assertFalse(slot.socketDir.contains("/data/data/com.termux"))
        assertFalse(Constants.INTEGRATED_X11_XKB_DIR.contains("/data/data/com.termux"))
    }
}
