package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerCapabilitiesTest {

    @Test
    fun alpinePackagePlatformDoesNotForceOpenRc() {
        val capabilities = ContainerCapabilitiesDetector.fromProbeResults(
            platform = ContainerPlatform.ALPINE,
            osReleaseLines = listOf("ID=alpine"),
            hasOpenRc = true,
            hasSystemd = true
        )

        assertEquals(ContainerPlatform.ALPINE, capabilities.platform)
        assertEquals(ContainerDistribution.ALPINE, capabilities.distribution)
        assertTrue(capabilities.supports(InitSystem.OPENRC))
        assertTrue(capabilities.supports(InitSystem.SYSTEMD))
    }

    @Test
    fun debPackagePlatformCanExposeOnlyOpenRc() {
        val capabilities = ContainerCapabilitiesDetector.fromProbeResults(
            platform = ContainerPlatform.UBUNTU,
            osReleaseLines = listOf("ID=debian"),
            hasOpenRc = true,
            hasSystemd = false
        )

        assertEquals(ContainerPlatform.UBUNTU, capabilities.platform)
        assertEquals(ContainerDistribution.DEBIAN, capabilities.distribution)
        assertTrue(capabilities.supports(InitSystem.OPENRC))
        assertFalse(capabilities.supports(InitSystem.SYSTEMD))
    }

    @Test
    fun unknownDistributionKeepsDetectedPackagePlatform() {
        val capabilities = ContainerCapabilitiesDetector.fromProbeResults(
            platform = ContainerPlatform.UBUNTU,
            osReleaseLines = listOf("ID=custom"),
            hasOpenRc = false,
            hasSystemd = true
        )

        assertEquals(ContainerDistribution.UNKNOWN, capabilities.distribution)
        assertEquals(ContainerPlatform.UBUNTU, capabilities.platform)
        assertEquals(setOf(InitSystem.SYSTEMD), capabilities.availableInitSystems)
    }
}
