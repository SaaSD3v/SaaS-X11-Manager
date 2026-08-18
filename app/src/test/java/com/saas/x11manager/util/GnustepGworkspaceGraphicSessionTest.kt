package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnustepGworkspaceGraphicSessionTest {

    @Test
    fun aptPlanUsesGworkspaceAndWindowMaker() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNUSTEP_GWORKSPACE
        )

        assertNotNull(plan)
        assertEquals(listOf("gworkspace.app", "wmaker", "dbus-x11", "xterm"), plan?.packages)
        assertEquals("saas-gnustep-gworkspace-session", GraphicSession.GNUSTEP_GWORKSPACE.startCommand)
    }

    @Test
    fun aptInstallPreflightsGworkspaceExecutableArtifact() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNUSTEP_GWORKSPACE
            )
        )
        val preflight = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Checking GNUstep GWorkspace package capability"
            }
        )

        assertTrue(preflight.command.contains("apt-get download gworkspace.app"))
        assertTrue(preflight.command.contains("usr/bin/GWorkspace"))
        assertFalse(preflight.command.contains("VERSION_ID="))
    }

    @Test
    fun wrapperRunsWindowMakerAndGworkspaceInSameDbusSession() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.GNUSTEP_GWORKSPACE))
        val command = spec.postInstallCommands.joinToString("\n") { it.command }

        assertTrue(command.contains("dbus-run-session"))
        assertTrue(command.contains("wmaker"))
        assertTrue(command.contains("exec GWorkspace"))
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.GNUSTEP_GWORKSPACE
            )
        )
    }
}
