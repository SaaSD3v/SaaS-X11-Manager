package com.saas.x11manager.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedFullscreenSurfacePolicyTest {

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
    fun fullscreenKeepsTheExistingEmbeddedSurface() {
        val navigation = source(
            "app/src/main/java/com/saas/x11manager/ui/navigation/AppNavigation.kt"
        )
        val display = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/ManagedDisplayScreen.kt"
        )

        assertFalse(navigation.contains("ManagedFullscreenDisplayScreen"))
        assertFalse(display.contains("onFullscreen: () -> Unit"))
        assertTrue(display.contains("key(\"managed-display-lorie-surface\")"))
        assertTrue(display.contains("setFullscreen(true)"))
        assertEquals(1, Regex("EmbeddedX11Surface\\(").findAll(display).count())
    }
}
