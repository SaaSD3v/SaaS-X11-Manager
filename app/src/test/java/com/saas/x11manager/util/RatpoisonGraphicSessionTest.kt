package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatpoisonGraphicSessionTest {
    @Test
    fun plansUseOfficialPackageAndCommand() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.RATPOISON)
            )
            assertEquals(listOf("ratpoison", "xterm"), plan.packages)
            assertEquals("ratpoison", plan.verificationCommand)
            val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
            assertTrue(commands.contains("command -v ratpoison"))
            assertFalse(commands.any { it.trim() == "ratpoison" })
        }
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.RATPOISON
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec ratpoison"))
        }
    }
}
