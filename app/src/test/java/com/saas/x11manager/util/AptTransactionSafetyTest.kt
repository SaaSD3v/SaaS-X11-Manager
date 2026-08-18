package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AptTransactionSafetyTest {

    @Test
    fun aptPlanSimulationUsesExactRecommendationPolicyAndRejectsUnsafeInfrastructure() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNOME_FLASHBACK
            )
        )
        val step = requireNotNull(AptTransactionSafety.stepFor(plan))

        assertEquals("Checking APT transaction safety", step.title)
        assertTrue(step.command.contains("apt-get -s --no-install-recommends install"))
        plan.packages.forEach { packageName ->
            assertTrue(step.command.contains(packageName))
        }
        assertTrue(step.command.contains("xserver-xorg.*"))
        assertTrue(step.command.contains("gdm3"))
        assertTrue(step.command.contains("lightdm"))
        assertTrue(step.command.contains("sddm"))
        assertTrue(step.command.contains("pulseaudio"))
        assertTrue(step.command.contains("pipewire-pulse"))
        assertTrue(step.command.contains("Remv"))
        assertFalse(step.command.contains("VERSION_ID="))
    }

    @Test
    fun apkPlansDoNotCreateAptSafetyStep() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.XFCE
            )
        )

        assertTrue(AptTransactionSafety.stepFor(plan) == null)
    }

    @Test
    fun genericInstallerRunsSafetyCheckBeforeAtomicAptInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNOME_FLASHBACK
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val safetyIndex = steps.indexOfFirst { it.title == "Checking APT transaction safety" }
        val installIndex = steps.indexOfFirst { it.title.startsWith("Installing ") }

        assertTrue(safetyIndex >= 0)
        assertTrue(installIndex > safetyIndex)
    }
}
