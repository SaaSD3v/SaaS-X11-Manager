package com.saas.x11manager.ui.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedStylusInputPolicyTest {

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
    fun stylusEventsAreRoutedBeforeTouchAndMouseFallbacks() {
        val content = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/EmbeddedX11Content.kt"
        )

        assertTrue(content.contains("EmbeddedStylusInputController(this)"))
        assertTrue(content.contains("stylusInput.updateInputTransform"))
        val stylusRoute = content.indexOf("stylusInput.handles(event)")
        val touchRoute = content.indexOf("touchInput.handles(event)")
        assertTrue(stylusRoute >= 0 && touchRoute > stylusRoute)
    }

    @Test
    fun embeddedStylusPreservesXinputPressureTiltButtonsAndEraserState() {
        val controller = source(
            "embedded-lorie/src/main/java/com/termux/x11/EmbeddedStylusInputController.java"
        )

        assertTrue(controller.contains("MotionEvent.TOOL_TYPE_STYLUS"))
        assertTrue(controller.contains("MotionEvent.TOOL_TYPE_ERASER"))
        assertTrue(controller.contains("MotionEvent.AXIS_TILT"))
        assertTrue(controller.contains("MotionEvent.AXIS_ORIENTATION"))
        assertTrue(controller.contains("BUTTON_STYLUS_PRIMARY"))
        assertTrue(controller.contains("BUTTON_STYLUS_SECONDARY"))
        assertTrue(controller.contains("sender.sendStylusEvent("))
        assertTrue(controller.contains("event.getPressure(index)"))
        assertTrue(controller.contains("clamp(mappedPoint[0]"))
        assertTrue(controller.contains("convertOrientation(orientation)"))
    }

    @Test
    fun embeddedStylusHonorsExistingStylusPreferencesAndCaptureLifecycle() {
        val controller = source(
            "embedded-lorie/src/main/java/com/termux/x11/EmbeddedStylusInputController.java"
        )

        assertTrue(controller.contains("prefs.stylusIsMouse.get()"))
        assertTrue(controller.contains("prefs.stylusButtonContactModifierMode.get()"))
        assertTrue(controller.contains("EmbeddedDisplayHost.setCapturingEnabled(true)"))
    }
}
