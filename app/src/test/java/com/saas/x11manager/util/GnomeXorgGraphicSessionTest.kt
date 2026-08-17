package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeXorgGraphicSessionTest {

    @Test
    fun aptPlanUsesDebianGnomeXorgSessionPackages() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNOME_XORG
        )

        assertNotNull(plan)
        assertEquals(
            listOf("gnome-session", "gnome-session-xsession", "dbus-x11", "xterm"),
            plan?.packages
        )
        assertEquals("saas-gnome-xorg-session", GraphicSession.GNOME_XORG.startCommand)
    }

    @Test
    fun wrapperLaunchesRealGnomeSessionThroughDbus() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.GNOME_XORG))
        assertTrue(spec.postInstallCommands.any {
            it.command.contains("dbus-run-session -- gnome-session")
        })
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.GNOME_XORG
            )
        )
    }
}
