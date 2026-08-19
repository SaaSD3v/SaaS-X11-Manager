package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedSettingsScopePolicyTest {

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
    fun managerConfigurationOwnsAdvancedSettingsThatEmbeddedHostSupports() {
        val dialogs = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/DisplayDialogs.kt"
        )

        assertTrue(dialogs.contains("transformCapturedPointer"))
        assertTrue(dialogs.contains("capturedPointerSpeedFactor"))
        assertTrue(dialogs.contains("hardwareKbdScancodesWorkaround"))
        assertTrue(dialogs.contains("extra_keys_config"))
        assertTrue(dialogs.contains("if (pointerCapture)"))
        assertTrue(dialogs.contains("Leave blank to use the built-in layout."))
        assertTrue(dialogs.contains("Only settings implemented by the embedded X11 host"))
        assertFalse(dialogs.contains("Advanced X11 settings"))
        assertFalse(dialogs.contains("openLorieSettings"))
    }

    @Test
    fun embeddedContentDoesNotLaunchStandaloneLoriePreferences() {
        val content = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/EmbeddedX11Content.kt"
        )

        assertFalse(content.contains("LoriePreferences"))
        assertFalse(content.contains("openLorieSettings"))
    }

    @Test
    fun mergedAppRemovesStandaloneLorieComponentsButKeepsBinderBridgeAvailable() {
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("com.termux.x11.MainActivity"))
        assertTrue(manifest.contains("com.termux.x11.LoriePreferences"))
        assertTrue(manifest.contains("com.termux.x11.utils.KeyInterceptor"))
        assertTrue(manifest.contains("com.termux.x11.LoriePreferences\$Receiver"))
        assertTrue(manifest.contains("android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifest.contains("tools:node=\"remove\""))

        // LorieBroadcastReceiver is intentionally inherited from embedded-lorie.
        // CmdEntryPoint sends ACTION_START to it to deliver the X server Binder.
        assertFalse(manifest.contains("com.termux.x11.LorieBroadcastReceiver"))
    }
}
