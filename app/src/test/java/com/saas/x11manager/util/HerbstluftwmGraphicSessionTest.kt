package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerbstluftwmGraphicSessionTest {
    @Test
    fun plansUseOfficialPackageAndCommand() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.HERBSTLUFTWM)
            )
            assertEquals(listOf("herbstluftwm", "xterm"), plan.packages)
            assertEquals("herbstluftwm", plan.verificationCommand)
            val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
            assertTrue(commands.contains("command -v herbstluftwm"))
            assertFalse(commands.any { it.trim() == "herbstluftwm" })
        }
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.HERBSTLUFTWM
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec herbstluftwm"))
        }
    }
}
