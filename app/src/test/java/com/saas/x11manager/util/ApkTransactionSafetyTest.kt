package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkTransactionSafetyTest {

    @Test
    fun apkPlanSimulationRejectsHostOwnedInfrastructureWithoutVersionChecks() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.XFCE
            )
        )
        val step = requireNotNull(ApkTransactionSafety.stepFor(plan))

        assertEquals("Checking apk transaction safety", step.title)
        assertTrue(step.command.contains("apk --simulate add"))
        plan.packages.forEach { packageName ->
            assertTrue(step.command.contains(packageName))
        }
        assertTrue(step.command.contains("xorg-server"))
        assertTrue(step.command.contains("lightdm"))
        assertTrue(step.command.contains("sddm"))
        assertTrue(step.command.contains("gdm"))
        assertTrue(step.command.contains("pulseaudio"))
        assertTrue(step.command.contains("pipewire-pulse"))
        assertFalse(step.command.contains("VERSION_ID="))
    }

    @Test
    fun aptPlansDoNotCreateApkSafetyStep() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.XFCE
            )
        )

        assertTrue(ApkTransactionSafety.stepFor(plan) == null)
    }

    @Test
    fun genericInstallerRunsSafetyCheckAfterIndexRefreshAndBeforeAtomicApkInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.XFCE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val updateIndex = steps.indexOfFirst { it.command == "apk update" }
        val safetyIndex = steps.indexOfFirst { it.title == "Checking apk transaction safety" }
        val installIndex = steps.indexOfFirst { it.title.startsWith("Installing ") }

        assertTrue(updateIndex >= 0)
        assertTrue(safetyIndex > updateIndex)
        assertTrue(installIndex > safetyIndex)
    }
}
