package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression guard for both VNC settings surfaces. */
class VncSettingsFullscreenPolicyTest {

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
    fun generalAndAdvancedVncSettingsUseFullScreenDialogs() {
        val general = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/GeneralSettingsDialog.kt"
        )
        val advanced = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/TigerVncSettingsDialog.kt"
        )

        val shared = source(
            "app/src/main/java/com/saas/x11manager/ui/component/SettingsComponents.kt"
        )
        assertFalse(general.contains("AlertDialog("))
        assertTrue(general.contains("SettingsDialog("))
        assertTrue(advanced.contains("SettingsDialog("))
        assertTrue(shared.contains("usePlatformDefaultWidth = false"))
        assertTrue(shared.contains("decorFitsSystemWindows = false"))
        assertTrue(shared.contains("Modifier.fillMaxSize()"))
        assertTrue(shared.contains("shape = RectangleShape"))
    }
}
