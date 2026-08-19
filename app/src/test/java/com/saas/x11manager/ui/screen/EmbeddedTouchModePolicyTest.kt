package com.saas.x11manager.ui.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedTouchModePolicyTest {

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
    fun embeddedSurfaceRoutesFingerEventsThroughTouchModeController() {
        val content = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/EmbeddedX11Content.kt"
        )

        assertTrue(content.contains("EmbeddedTouchInputController(this)"))
        assertTrue(content.contains("touchInput.updateInputTransform"))
        assertTrue(content.contains("touchInput.handles(event)"))
        assertTrue(content.contains("EmbeddedDisplayHost.handleMotion(this, event)"))
    }

    @Test
    fun embeddedTouchControllerImplementsAllThreeTermuxX11TouchModes() {
        val controller = source(
            "embedded-lorie/src/main/java/com/termux/x11/EmbeddedTouchInputController.java"
        )

        assertTrue(controller.contains("prefs.touchMode.get()"))
        assertTrue(controller.contains("TrackpadInputStrategy"))
        assertTrue(controller.contains("SimulatedTouchInputStrategy"))
        assertTrue(controller.contains("sender.sendTouchEvent(event, renderData)"))
        assertTrue(controller.contains("TapGestureDetector"))
        assertTrue(controller.contains("MotionEvent.TOOL_TYPE_FINGER"))
        assertTrue(controller.contains("EmbeddedDisplayHost.setCapturingEnabled(true)"))
    }

    @Test
    fun trackpadGesturesKeepMultiFingerClicksScrollAndTapToMove() {
        val controller = source(
            "embedded-lorie/src/main/java/com/termux/x11/EmbeddedTouchInputController.java"
        )

        assertTrue(controller.contains("case 2:\n                return InputStub.BUTTON_RIGHT"))
        assertTrue(controller.contains("case 3:\n                return InputStub.BUTTON_MIDDLE"))
        assertTrue(controller.contains("strategy.onScroll(distanceX, distanceY)"))
        assertTrue(controller.contains("sender.tapToMove"))
        assertTrue(controller.contains("onPressAndHold(InputStub.BUTTON_LEFT, true)"))
    }
}
