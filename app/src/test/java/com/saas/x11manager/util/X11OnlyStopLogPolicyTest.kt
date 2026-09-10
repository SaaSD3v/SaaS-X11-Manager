package com.saas.x11manager.util

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class X11OnlyStopLogPolicyTest {

    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        return File(System.getProperty("user.dir"), relativePath)
    }

    @Test
    fun fixedX0ContainerStopAlwaysEmitsLifecycleCheckpoints() {
        val source = projectFile(
            "app/src/main/java/com/saas/x11manager/util/X11SessionManager.kt"
        ).readText()

        assertTrue(source.contains("--- Stopping Container X11 Session ---"))
        assertTrue(source.contains("[CTX] Container: $containerName".replace("$containerName", "\$containerName")))
        assertTrue(source.contains("[+] Container stop confirmed"))
        assertTrue(source.contains("[CTX] Runtime policy: "))
    }

    @Test
    fun conciseReducerKeepsSuccessfulSilentStopVisible() {
        val reducer = ConciseLogReducer()
        val output = buildList {
            addAll(reducer.reduce(Log.INFO, "--- Stopping Container X11 Session ---"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Container: debian"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Assigned display before stop: :0"))
            addAll(reducer.reduce(Log.INFO, "[+] Container stop confirmed"))
            addAll(reducer.reduce(Log.INFO, "[CTX] Runtime policy: X0 retained for active owner alpine"))
        }.map { it.second }.filter { it.isNotEmpty() }

        assertEquals(
            listOf(
                "[CONTAINER] Stopping container and releasing X11",
                "[CONTAINER] • Container: debian",
                "[X11] • Assigned display: :0",
                "[CONTAINER] ✓ Container stopped",
                "[X11] • Cleanup: X0 retained for active owner alpine"
            ),
            output
        )
    }
}
