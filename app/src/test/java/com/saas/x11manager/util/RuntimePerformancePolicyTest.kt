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
    fun integratedStartReusesAuthoritativeDesktopAndBatchesRuntimeProbe() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/X11SessionManager.kt"
        )
        val access = source(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        )

        assertTrue(manager.contains("data class X11SessionStartResult"))
        assertTrue(manager.contains("private fun probeServerRuntime(displaySlot: X11DisplaySlot)"))
        assertTrue(manager.contains("private fun probeRuntimeSnapshot("))
        assertTrue(manager.contains("parseRuntimeSnapshot(result.out)"))
        assertTrue(manager.contains("val runtime = probeRuntimeSnapshot(assignments.keys)"))
        assertTrue(manager.contains("val runtime = probeServerRuntime(displaySlot)"))
        assertTrue(manager.contains("X11SessionStartResult(displaySlot, graphicSessionReady)"))
        assertTrue(access.contains("startResult.graphicSessionReady ||"))
        assertTrue(access.contains("desktop.confirm-retry"))
        assertFalse(access.contains("perf.stage(\"desktop.confirm\")"))
        assertFalse(manager.contains("discoverRuntimeSlots().map { it.number }"))
        assertFalse(manager.contains("val socketTable = socketTableLines()\n\n            slotNumbers"))
    }

    @Test
    fun lifecycleOperationsReuseKnownContainerSnapshots() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/X11SessionManager.kt"
        )
        val home = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/HomeViewModel.kt"
        )

        assertTrue(manager.contains("if (releasedBindings > 0)"))
        assertTrue(manager.contains("containersSnapshot ?: ContainerManager.listContainers()"))
        assertTrue(manager.contains("val remainingOwners = runningAssignments(containersAfterStop)"))
        assertTrue(home.contains("X11SessionManager.stopAll(logger, currentContainers)"))
        assertTrue(home.contains("if (!runtimeRefreshed) refreshRuntimeAfterOperation()"))
        assertTrue(home.contains("ViewModelLogger.batched"))
        assertTrue(home.contains("appendLogs(logs, entries)"))
    }

    @Test
    fun composeStateCollectionStopsOutsideActiveLifecycle() {
        val screens = listOf(
            "RequirementsScreen.kt",
            "HomeScreen.kt",
            "DisplayScreen.kt",
            "ManagedDisplayScreen.kt"
        ).map { source("app/src/main/java/com/saas/x11manager/ui/screen/$it") } + listOf(
            source("app/src/main/java/com/saas/x11manager/ui/screen/vnc/VncLauncherScreen.kt"),
            source("app/src/main/java/com/saas/x11manager/ui/screen/vnc/VncManagedScreen.kt")
        )

        screens.forEach { screen ->
            assertTrue(screen.contains("collectAsStateWithLifecycle()"))
            assertFalse(screen.contains(".collectAsState()"))
        }
    }
}
