package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FluxboxGraphicSessionTest {

    @Test
    fun fluxboxPlansUseExactArmRepositoryPackageNames() {
        val alpine = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.FLUXBOX)
        )
        val deb = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.FLUXBOX)
        )

        assertEquals(listOf("fluxbox", "xterm"), alpine.packages)
        assertEquals(listOf("fluxbox", "xterm"), deb.packages)
        assertEquals("startfluxbox", alpine.verificationCommand)
        assertEquals("startfluxbox", deb.verificationCommand)
        assertFalse(deb.installRecommendedPackages)
    }

    @Test
    fun fluxboxInstallerValidatesButNeverLaunchesSession() {
        ContainerPlatform.entries.forEach { platform ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(platform, GraphicSession.FLUXBOX)
            )
            val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }

            assertTrue(commands.contains("command -v startfluxbox"))
            assertFalse(commands.any { it.trim() == "startfluxbox" })
            assertFalse(commands.any { it.contains("exec startfluxbox") })
        }
    }

    @Test
    fun existingStartupTemplatesAcceptFluxboxWithoutSpecialInitPath() {
        ContainerPlatform.entries.forEach { platform ->
            InitSystem.entries.forEach { initSystem ->
                val joined = GraphicSessionInstaller.startupStepsFor(
                    platform,
                    initSystem,
                    GraphicSession.FLUXBOX
                ).joinToString("\n") { it.command }

                assertTrue(joined.contains("exec startfluxbox"))
                assertTrue(joined.contains("/usr/local/bin/x11-session.sh"))
            }
        }
    }
}
