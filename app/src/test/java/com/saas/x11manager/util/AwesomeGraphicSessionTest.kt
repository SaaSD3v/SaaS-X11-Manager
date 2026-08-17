package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwesomeGraphicSessionTest {
    @Test
    fun plansUseOfficialPackageAndCommand() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.AWESOME)
            )
            assertEquals(listOf("awesome", "xterm"), plan.packages)
            assertEquals("awesome", plan.verificationCommand)
            val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
            assertTrue(commands.contains("command -v awesome"))
            assertFalse(commands.any { it.trim() == "awesome" })
        }
    }

    @Test
    fun verificationUsesSyntaxCheckWithoutLaunchingWindowManager() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.AWESOME))
        val verify = spec.verificationCommands.joinToString("\n") { it.command }

        assertTrue(verify.contains("awesome --check"))
        assertFalse(verify.contains("exec awesome"))
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.AWESOME
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec awesome"))
        }
    }
}
