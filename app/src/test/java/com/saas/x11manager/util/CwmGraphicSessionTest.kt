package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CwmGraphicSessionTest {

    @Test
    fun cwmPlansUseExactPackagesAndCommand() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.CWM)
            )
            assertEquals(listOf("cwm", "xterm"), plan.packages)
            assertEquals("cwm", plan.verificationCommand)
        }
    }

    @Test
    fun cwmInstallerValidatesConfigurationWithoutLaunchingSession() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.CWM)
            )
            val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }

            assertTrue(commands.contains("cwm -n"))
            assertTrue(commands.contains("command -v cwm"))
            assertFalse(commands.any { it.trim() == "cwm" })
            assertFalse(commands.any { it.contains("exec cwm") })
        }
    }

    @Test
    fun existingStartupTemplatesAcceptCwm() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val joined = GraphicSessionInstaller.startupStepsFor(
                    platform,
                    initSystem,
                    GraphicSession.CWM
                ).joinToString("\n") { it.command }
                assertTrue(joined.contains("exec cwm"))
            }
        }
    }
}
