package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegratedX11CleanupPolicyTest {

    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        return File(System.getProperty("user.dir"), relativePath)
    }

    private fun source(relativePath: String): String {
        val file = projectFile(relativePath)
        check(file.isFile) { "Could not locate source file: $relativePath" }
        return file.readText()
    }

    @Test
    fun obsoleteDisplayImplementationsStayRemoved() {
        val screenRoot = "app/src/main/java/com/saas/x11manager/ui/screen"
        assertFalse(projectFile("$screenRoot/ManagedFullscreenDisplayScreen.kt").exists())
        assertFalse(projectFile("$screenRoot/SystemInfoScreen.kt").exists())

        val embedded = source("$screenRoot/EmbeddedX11Content.kt")
        val dialogs = source("$screenRoot/DisplayDialogs.kt")
        assertFalse(embedded.contains("fun FullscreenDisplayScreen("))
        assertFalse(dialogs.contains("fun X11ScreenDialog("))
    }

    @Test
    fun displayLandingPageOnlyOwnsConfigurationAndScreenLaunchers() {
        val display = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/DisplayScreen.kt"
        )
        assertFalse(display.contains("Current backend"))
        assertFalse(display.contains("DisplaySummaryRow"))
        assertTrue(display.contains("title = \"Configuration\""))
        assertTrue(display.contains("title = \"Screen\""))
    }

    @Test
    fun managerKeyboardDefaultsRemainOptIn() {
        val dialogs = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/DisplayDialogs.kt"
        )
        assertTrue(dialogs.contains("getBoolean(PREF_SHOW_ADDITIONAL_KEYS, false)"))
        assertTrue(dialogs.contains("getBoolean(PREF_SHOW_IME_WITH_EXTERNAL_KEYBOARD, false)"))
    }
}
