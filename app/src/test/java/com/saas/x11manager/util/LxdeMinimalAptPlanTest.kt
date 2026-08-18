package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxdeMinimalAptPlanTest {

    @Test
    fun lxdeUsesExplicitSessionComponentsAndSafeLockerProvider() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.LXDE
            )
        )

        assertEquals(
            listOf(
                "openbox-lxde-session",
                "lxpanel",
                "pcmanfm",
                "lxterminal",
                "dbus-x11",
                "suckless-tools"
            ),
            plan.packages
        )
        assertFalse("lxde-core" in plan.packages)
        assertFalse("lightdm" in plan.packages)
        assertTrue("suckless-tools" in plan.packages)
        assertFalse(plan.installRecommendedPackages)
    }

    @Test
    fun lxdeInstallKeepsTransactionSafetyBeforeInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.LXDE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val safetyIndex = steps.indexOfFirst { it.title == "Checking APT transaction safety" }
        val installIndex = steps.indexOfFirst { it.command.contains("apt-get install -y") }

        assertTrue(safetyIndex >= 0)
        assertTrue(installIndex > safetyIndex)
    }
}
