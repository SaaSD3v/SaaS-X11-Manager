package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimePerformancePolicyTest {

    private fun source(relativePath: String): String {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile
        }
        error("Could not locate source file: $relativePath")
    }

    @Test
    fun fixedX0UsesOneBoundedProbeAndNoWholeProcProcessScan() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/X11SessionManager.kt"
        )
        val home = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/HomeViewModel.kt"
        )

        assertTrue(manager.contains("private fun probeServerRuntime()"))
        assertTrue(manager.contains("suspend fun getServerRuntime(): X11ServerRuntime"))
        assertFalse(manager.contains("for comm in /proc/[0-9]*/comm"))
        assertTrue(home.contains("val x11 = X11SessionManager.getServerRuntime()"))
        assertFalse(home.contains("x11Status = X11SessionManager.getServerStatus()"))
        assertFalse(home.contains("x11Pid = X11SessionManager.getServerPid()"))
    }

    @Test
    fun successfulFixedX0StartDoesNotConfirmDesktopTwice() {
        val access = source(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        )
        assertFalse(access.contains("confirmManagedDesktop("))
        assertTrue(access.contains("startX11Session returns true only after"))
    }

    @Test
    fun stopAllAndComposeCollectorsAvoidRedundantBackgroundWork() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/X11SessionManager.kt"
        )
        val home = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/HomeViewModel.kt"
        )
        assertTrue(manager.contains("containersSnapshot"))
        assertTrue(manager.contains("?.takeIf { it.isNotEmpty() }"))
        assertTrue(home.contains("X11SessionManager.stopAll(logger, currentContainers)"))
        assertTrue(home.contains("if (!runtimeRefreshed) refreshRuntimeAfterOperation()"))
        assertTrue(home.contains("ViewModelLogger.batched"))
        assertTrue(home.contains("appendLogs(logs, entries)"))

        listOf(
            "RequirementsScreen.kt",
            "HomeScreen.kt",
            "DisplayScreen.kt",
            "ManagedDisplayScreen.kt"
        ).forEach { name ->
            val screen = source("app/src/main/java/com/saas/x11manager/ui/screen/$name")
            assertTrue(screen.contains("collectAsStateWithLifecycle()"))
            assertFalse(screen.contains(".collectAsState()"))
        }
    }
}
