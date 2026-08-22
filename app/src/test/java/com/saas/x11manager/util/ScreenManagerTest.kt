package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenManagerTest {

    @Test
    fun preferencePayloadContainsOnlyEmbeddedRendererKeys() {
        val config = ScreenConfig(
            resolutionMode = ScreenResolutionMode.Scaled,
            scalePercent = 140,
            exactResolution = "1920x1080",
            customResolution = "1600x900",
            filtering = ScreenFiltering.Bilinear,
            adjustResolution = true,
            stretch = true,
            clipboard = false,
            touchMode = ScreenTouchMode.DirectTouch,
            keepScreenAwake = true,
            showAdditionalKeyboard = true
        )

        val payload = ScreenManager.buildPreferencePayload(config)

        assertEquals("scaled", payload["displayResolutionMode"])
        assertEquals("140", payload["displayScale"])
        assertEquals("1920x1080", payload["displayResolutionExact"])
        assertEquals("1600x900", payload["displayResolutionCustom"])
        assertEquals("bilinear", payload["displayFilteringMode"])
        assertEquals("true", payload["adjustResolution"])
        assertEquals("true", payload["displayStretch"])
        assertEquals("false", payload["clipboardEnable"])

        assertFalse(payload.containsKey("fullscreen"))
        assertFalse(payload.containsKey("forceOrientation"))
        assertFalse(payload.containsKey("hideCutout"))
        assertFalse(payload.containsKey("touchMode"))
        assertFalse(payload.containsKey("screenIdleTimeout"))
        assertFalse(payload.containsKey("showAdditionalKbd"))
    }

    @Test
    fun hostOwnedInputSettingsDoNotLeakIntoLoriePayload() {
        val defaults = ScreenConfig()
        val enabledHostControls = defaults.copy(
            touchMode = ScreenTouchMode.DirectTouch,
            keepScreenAwake = true,
            showAdditionalKeyboard = true
        )

        assertFalse(defaults.showAdditionalKeyboard)
        assertEquals(
            ScreenManager.buildPreferencePayload(defaults),
            ScreenManager.buildPreferencePayload(enabledHostControls)
        )
    }

    @Test
    fun realLorieAdjustResolutionSettingIsForwarded() {
        assertEquals(
            "false",
            ScreenManager.buildPreferencePayload(ScreenConfig())["adjustResolution"]
        )
        assertEquals(
            "true",
            ScreenManager.buildPreferencePayload(
                ScreenConfig(adjustResolution = true)
            )["adjustResolution"]
        )
    }

    @Test
    fun uiNormalizationPreservesPartialCustomResolutionDraft() {
        val normalized = ScreenConfig(
            scalePercent = 999,
            exactResolution = "not-supported",
            customResolution = "192"
        ).normalized()

        assertEquals(300, normalized.scalePercent)
        assertEquals(ScreenManager.DEFAULT_RESOLUTION, normalized.exactResolution)
        assertEquals("192", normalized.customResolution)
        assertFalse(normalized.isCustomResolutionValid())
        assertTrue(normalized.clipboard)
    }

    @Test
    fun invalidCustomResolutionFallsBackOnlyAtRendererBoundary() {
        val config = ScreenConfig(
            resolutionMode = ScreenResolutionMode.Custom,
            customResolution = "99999x99999"
        )

        assertEquals("99999x99999", config.normalized().customResolution)
        assertFalse(config.isCustomResolutionValid())
        assertEquals(
            ScreenManager.DEFAULT_RESOLUTION,
            ScreenManager.buildPreferencePayload(config)["displayResolutionCustom"]
        )
    }

    @Test
    fun rendererRejectsFramebufferThatExceedsPixelBudget() {
        val config = ScreenConfig(
            resolutionMode = ScreenResolutionMode.Custom,
            customResolution = "8192x8192"
        )

        assertFalse(config.isCustomResolutionValid())
        assertEquals(
            ScreenManager.DEFAULT_RESOLUTION,
            ScreenManager.buildPreferencePayload(config)["displayResolutionCustom"]
        )
    }

    @Test
    fun rendererAcceptsLargeResolutionWithinDimensionAndPixelBudgets() {
        val config = ScreenConfig(
            resolutionMode = ScreenResolutionMode.Custom,
            customResolution = "4096x2160"
        )

        assertTrue(config.isCustomResolutionValid())
        assertEquals(
            "4096x2160",
            ScreenManager.buildPreferencePayload(config)["displayResolutionCustom"]
        )
    }
}
