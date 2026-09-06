package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FullscreenBackPolicyTest {

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
    fun activityOwnsFullscreenBackBeforeTheX11Viewport() {
        val activity = source(
            "app/src/main/java/com/saas/x11manager/MainActivity.kt"
        )
        val display = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/ManagedDisplayScreen.kt"
        )
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(activity.contains("override fun dispatchKeyEvent(event: KeyEvent)"))
        assertTrue(activity.contains("KeyEvent.KEYCODE_BACK"))
        assertTrue(activity.contains("OnBackInvokedDispatcher.PRIORITY_OVERLAY"))
        assertTrue(activity.contains("isManagedX11Fullscreen()"))
        assertTrue(activity.contains("showFullscreenExitConfirmation()"))
        assertTrue(activity.contains("putBoolean(PREF_FULLSCREEN, false)"))
        assertTrue(activity.contains("publishLoriePreferenceChange(this, PREF_FULLSCREEN)"))
        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))

        // The fullscreen viewport must stay visually clean. ManagedDisplayScreen
        // handles Back only outside fullscreen; MainActivity owns fullscreen Back
        // and presents the existing confirmation dialog above the focused LorieView.
        assertTrue(display.contains("BackHandler(enabled = !fullscreen)"))
        assertFalse(display.contains("FullscreenDisplayControls("))
        assertFalse(display.contains("Popup("))
        assertFalse(display.contains("if (fullscreen) setFullscreen(false) else onClose()"))

        assertFalse(activity.contains("stopIntegratedServer"))
        assertFalse(activity.contains("stopX11Session"))
        assertFalse(activity.contains("stopContainer"))
    }
}
