package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrwmGraphicSessionTest {
    @Test
    fun plansUseOfficialPackageAndCommand() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.SPECTRWM)
            )
            assertEquals(listOf("spectrwm", "xterm"), plan.packages)
            assertEquals("spectrwm", plan.verificationCommand)
            val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
            assertTrue(commands.contains("command -v spectrwm"))
            assertFalse(commands.any { it.trim() == "spectrwm" })
        }
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.SPECTRWM
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec spectrwm"))
        }
    }
}
