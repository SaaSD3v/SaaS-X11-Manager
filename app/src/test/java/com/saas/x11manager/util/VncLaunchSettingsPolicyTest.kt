package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VncLaunchSettingsPolicyTest {

    @Test
    fun geometryValidationAcceptsNormalDesktopSizesAndRejectsInvalidInput() {
        assertTrue(VncSettings.isValidGeometry("1280x720"))
        assertTrue(VncSettings.isValidGeometry("1920X1080"))
        assertTrue(VncSettings.isValidGeometry(" 2560 x 1440 "))
        assertFalse(VncSettings.isValidGeometry("1920"))
        assertFalse(VncSettings.isValidGeometry("1x1"))
        assertFalse(VncSettings.isValidGeometry("99999x720"))
    }

    @Test
    fun defaultSettingsPreserveExistingStandaloneLaunchPolicy() {
        val settings = VncLaunchSettings()
        assertNull(VncSettings.validateLaunchSettings(settings))

        val args = TigerVncCommandOptions.standalone(
            settings = settings,
            displayNumber = 1,
            port = 5901,
            passwordFile = "/root/.vnc/passwd"
        )

        assertEquals(":1", args.first())
        assertTrue(args.windowed("-geometry", "1280x720"))
        assertTrue(args.windowed("-depth", "24"))
        assertTrue(args.windowed("-rfbport", "5901"))
        assertTrue(args.windowed("-localhost", "no"))
        assertTrue(args.windowed("-SecurityTypes", "VncAuth"))
        assertTrue(args.windowed("-rfbauth", "/root/.vnc/passwd"))
        assertTrue(args.contains("-AlwaysShared"))
        assertFalse(args.any { it.startsWith("-Password=") })
    }

    @Test
    fun customStandaloneResolutionAndAdvancedOptionsBecomeArgvTokens() {
        val settings = VncLaunchSettings(
            geometry = "1920x1080",
            depth = "32",
            frameRate = "30",
            acceptCutText = false,
            pixelFormat = "rgb888",
            extraArguments = "-ImprovedHextile=0"
        )

        val args = TigerVncCommandOptions.standalone(
            settings,
            displayNumber = 4,
            port = 5905,
            passwordFile = "/root/.vnc/passwd"
        )

        assertTrue(args.windowed("-geometry", "1920x1080"))
        assertTrue(args.windowed("-depth", "32"))
        assertTrue(args.windowed("-pixelformat", "rgb888"))
        assertTrue(args.contains("-FrameRate=30"))
        assertTrue(args.contains("-AcceptCutText=0"))
        assertTrue(args.contains("-ImprovedHextile=0"))
    }

    @Test
    fun mirrorOptionsKeepIntegratedDisplayAndSupportCropTuning() {
        val settings = VncLaunchSettings(
            mirrorGeometry = "1280x720+0+0",
            maxProcessorUsage = "55",
            pollingCycle = "40",
            useShm = false
        )

        val args = TigerVncCommandOptions.mirror(
            settings,
            displayName = ":12",
            port = 5901,
            passwordFile = "/root/.vnc/passwd"
        )

        assertTrue(args.windowed("-display", ":12"))
        assertTrue(args.windowed("-rfbport", "5901"))
        assertTrue(args.contains("-PasswordFile=/root/.vnc/passwd"))
        assertTrue(args.contains("-Geometry=1280x720+0+0"))
        assertTrue(args.contains("-MaxProcessorUsage=55"))
        assertTrue(args.contains("-PollingCycle=40"))
        assertTrue(args.contains("-UseSHM=0"))
    }

    @Test
    fun extraArgumentsCannotOverrideManagerOwnedPortDisplayOrPassword() {
        listOf(
            "-rfbport=5999",
            "-Password=secret",
            "-PasswordFile=/tmp/p",
            "-rfbauth=/tmp/p",
            "-display=:99"
        ).forEach { value ->
            val error = TigerVncCommandOptions.validateExtraArguments(value)
            assertTrue("Expected rejection for $value", error != null)
        }

        assertNull(TigerVncCommandOptions.validateExtraArguments("-FrameRate=30"))
    }

    @Test
    fun contradictorySharingAndNetworkSettingsFailClosed() {
        assertTrue(
            VncSettings.validateLaunchSettings(
                VncLaunchSettings(alwaysShared = true, neverShared = true)
            ) != null
        )
        assertTrue(
            VncSettings.validateLaunchSettings(
                VncLaunchSettings(useIPv4 = false, useIPv6 = false, rfbUnixPath = "")
            ) != null
        )
    }

    private fun List<String>.windowed(option: String, value: String): Boolean =
        windowed(2).any { it[0] == option && it[1] == value }
}
