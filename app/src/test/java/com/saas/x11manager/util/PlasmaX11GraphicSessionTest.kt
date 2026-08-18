package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlasmaX11GraphicSessionTest {

    @Test
    fun aptPlanKeepsTermuxX11SafeBaseWithoutLocalXorgProvider() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.PLASMA_X11
            )
        )

        assertEquals(
            listOf("plasma-desktop", "plasma-workspace", "kwin-x11", "dbus-x11", "xterm"),
            plan.packages
        )
        assertFalse(plan.installRecommendedPackages)
        assertFalse("plasma-session-x11" in plan.packages)
        assertFalse("xorg" in plan.packages)
    }

    @Test
    fun installerPreflightsLauncherInsideSafeWorkspaceCandidate() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.PLASMA_X11
            )
        )
        val preflight = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Checking Plasma X11 package capability"
            }
        )

        assertTrue(preflight.command.contains("apt-get download plasma-workspace"))
        assertTrue(preflight.command.contains("usr/bin/startplasma-x11"))
        assertTrue(preflight.command.contains("plasma-session-x11"))
        assertTrue(preflight.command.contains("local Xorg"))
        assertFalse(preflight.command.contains("apt-get install"))
        assertFalse(preflight.command.contains("VERSION_ID="))
    }
}
