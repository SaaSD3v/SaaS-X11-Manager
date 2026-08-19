package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenManagerTest {

    @Test
    fun preferencePayloadMatchesEmbeddedLorieContract() {
        val config = ScreenConfig(
            resolutionMode = ScreenResolutionMode.Scaled,
            scalePercent = 140,
            exactResolution = "1920x1080",
            customResolution = "1600x900",
            filtering = ScreenFiltering.Bilinear,
            stretch = true,
            fullscreen = true,
            orientation = ScreenOrientation.Landscape,
            hideCutout = true,
            clipboard = false,
            touchMode = ScreenTouchMode.DirectTouch,
            keepScreenAwake = true,
            showAdditionalKeyboard = false
        )

        val payload = ScreenManager.buildPreferencePayload(config)

        assertEquals("scaled", payload["displayResolutionMode"])
        assertEquals("140", payload["displayScale"])
        assertEquals("1920x1080", payload["displayResolutionExact"])
        assertEquals("1600x900", payload["displayResolutionCustom"])
        assertEquals("bilinear", payload["displayFilteringMode"])
        assertEquals("true", payload["displayStretch"])
        assertEquals("true", payload["fullscreen"])
        assertEquals("landscape", payload["forceOrientation"])
        assertEquals("true", payload["hideCutout"])
        assertEquals("false", payload["clipboardEnable"])
        assertEquals("3", payload["touchMode"])
        assertEquals("never", payload["screenIdleTimeout"])
        assertEquals("false", payload["showAdditionalKbd"])
    }

    @Test
    fun redesignedImeToolbarStartsDisabled() {
        val defaults = ScreenConfig()

        assertFalse(defaults.showAdditionalKeyboard)
        assertEquals("false", ScreenManager.buildPreferencePayload(defaults)["showAdditionalKbd"])
    }

    @Test
    fun invalidValuesAreNormalizedBeforeTheyReachLorie() {
        val normalized = ScreenConfig(
            scalePercent = 999,
            exactResolution = "not-supported",
            customResolution = "broken"
        ).normalized()

        assertEquals(300, normalized.scalePercent)
        assertEquals("1280x1024", normalized.exactResolution)
        assertEquals("1280x1024", normalized.customResolution)
    }
}
