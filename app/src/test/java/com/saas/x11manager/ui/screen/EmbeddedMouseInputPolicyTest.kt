package com.saas.x11manager.ui.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedMouseInputPolicyTest {

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
    fun embeddedSurfaceRoutesTheCompleteAndroidMouseEventSet() {
        val content = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/EmbeddedX11Content.kt"
        )

        assertTrue(content.contains("setOnHoverListener"))
        assertTrue(content.contains("setOnGenericMotionListener"))
        assertTrue(content.contains("setOnCapturedPointerListener"))
        assertTrue(content.contains("EmbeddedDisplayHost.handleMotion"))
    }

    @Test
    fun embeddedHostOwnsMouseButtonsWheelCaptureAndPointerVisibility() {
        val host = source(
            "embedded-lorie/src/main/java/com/termux/x11/EmbeddedDisplayHost.java"
        )

        assertTrue(host.contains("MotionEvent.BUTTON_SECONDARY, InputStub.BUTTON_RIGHT"))
        assertTrue(host.contains("MotionEvent.BUTTON_TERTIARY, InputStub.BUTTON_MIDDLE"))
        assertTrue(host.contains("sendMouseWheelEvent"))
        assertTrue(host.contains("requestPointerCapture"))
        assertTrue(host.contains("AXIS_RELATIVE_X"))
        assertTrue(host.contains("PointerIcon.TYPE_NULL"))
    }

    @Test
    fun embeddedHostAppliesKeyboardPreferencesWithoutStandaloneActivity() {
        val host = source(
            "embedded-lorie/src/main/java/com/termux/x11/EmbeddedDisplayHost.java"
        )

        assertTrue(host.contains("p.filterOutWinkey.get()"))
        assertTrue(host.contains("p.showIMEWhileExternalConnected.get()"))
        assertTrue(host.contains("setCapturingEnabled(false)"))
    }
}
