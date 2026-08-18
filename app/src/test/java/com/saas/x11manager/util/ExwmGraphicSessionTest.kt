package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExwmGraphicSessionTest {

    @Test
    fun aptPlanExplicitlyInstallsExwmAndGraphicalEmacs() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.EXWM
        )

        assertNotNull(plan)
        assertEquals(listOf("elpa-exwm", "emacs-gtk", "dbus-x11", "xterm"), plan?.packages)
        assertEquals("saas-exwm-session", GraphicSession.EXWM.startCommand)
    }

    @Test
    fun aptInstallPreflightsExwmX11SessionArtifact() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.EXWM
            )
        )
        val preflight = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Checking EXWM X11 package capability"
            }
        )

        assertTrue(preflight.command.contains("apt-get download elpa-exwm"))
        assertTrue(preflight.command.contains("usr/share/xsessions/exwm.desktop"))
        assertFalse(preflight.command.contains("VERSION_ID="))
    }

    @Test
    fun wrapperUsesCurrentExwmApiWithLegacyFallback() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.EXWM))
        val command = spec.postInstallCommands.joinToString("\n") { it.command }

        assertTrue(command.contains("emacs-gtk"))
        assertTrue(command.contains("exwm-wm-mode"))
        assertTrue(command.contains("exwm-enable"))
        assertTrue(command.contains("_JAVA_AWT_WM_NONREPARENTING=1"))
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.EXWM
            )
        )
    }
}
