package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerProfileDefaultsTest {

    @Test
    fun alpineDetectionDefaultsToAlpineOpenrcAndXfce() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.ALPINE,
            savedPlatform = null,
            savedInitSystem = null,
            savedGraphicSession = null
        )

        assertEquals(ContainerPlatform.ALPINE, selection.platform)
        assertEquals(InitSystem.OPENRC, selection.initSystem)
        assertEquals(GraphicSession.XFCE, selection.graphicSession)
    }

    @Test
    fun ubuntuDetectionDefaultsToUbuntuSystemdAndXfce() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.UBUNTU,
            savedPlatform = null,
            savedInitSystem = null,
            savedGraphicSession = null
        )

        assertEquals(ContainerPlatform.UBUNTU, selection.platform)
        assertEquals(InitSystem.SYSTEMD, selection.initSystem)
        assertEquals(GraphicSession.XFCE, selection.graphicSession)
    }

    @Test
    fun debianDetectionUsesUbuntuAptProfileWithoutChangingDistribution() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.DEBIAN,
            savedPlatform = null,
            savedInitSystem = null,
            savedGraphicSession = null
        )

        assertEquals(ContainerDistribution.DEBIAN, selection.distribution)
        assertEquals(ContainerPlatform.UBUNTU, selection.platform)
        assertEquals(InitSystem.SYSTEMD, selection.initSystem)
    }

    @Test
    fun savedInitAlwaysWinsOverDetectedDefault() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.ALPINE,
            savedPlatform = null,
            savedInitSystem = InitSystem.SYSTEMD,
            savedGraphicSession = null
        )

        assertEquals(ContainerPlatform.ALPINE, selection.platform)
        assertEquals(InitSystem.SYSTEMD, selection.initSystem)
    }

    @Test
    fun savedPlatformWinsAndSuppliesDefaultInitWhenInitWasNeverSaved() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.UBUNTU,
            savedPlatform = ContainerPlatform.ALPINE,
            savedInitSystem = null,
            savedGraphicSession = null
        )

        assertEquals(ContainerPlatform.ALPINE, selection.platform)
        assertEquals(InitSystem.OPENRC, selection.initSystem)
    }

    @Test
    fun savedGraphicSessionAlwaysWins() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.ALPINE,
            savedPlatform = null,
            savedInitSystem = null,
            savedGraphicSession = GraphicSession.LXQT
        )

        assertEquals(GraphicSession.LXQT, selection.graphicSession)
    }

    @Test
    fun unknownDistributionFallsBackWithoutPretendingItWasDetected() {
        val selection = ContainerProfileDefaults.resolve(
            distribution = ContainerDistribution.UNKNOWN,
            savedPlatform = null,
            savedInitSystem = null,
            savedGraphicSession = null
        )

        assertEquals(ContainerDistribution.UNKNOWN, selection.distribution)
        assertEquals(ContainerPlatform.UBUNTU, selection.platform)
        assertEquals(InitSystem.SYSTEMD, selection.initSystem)
    }
}
