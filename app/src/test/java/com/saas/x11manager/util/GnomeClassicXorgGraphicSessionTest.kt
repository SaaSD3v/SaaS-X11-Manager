package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeClassicXorgGraphicSessionTest {

    @Test
    fun aptPlanUsesDebianClassicXorgPackages() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNOME_CLASSIC_XORG
        )

        assertNotNull(plan)
        assertEquals(
            listOf("gnome-classic", "gnome-classic-xsession", "dbus-x11", "xterm"),
            plan?.packages
        )
    }

    @Test
    fun wrapperUsesDebianClassicSessionLauncher() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.GNOME_CLASSIC_XORG))
        assertEquals("saas-gnome-classic-xorg-session", GraphicSession.GNOME_CLASSIC_XORG.startCommand)
        assertTrue(spec.postInstallCommands.any {
            it.command.contains("dbus-run-session -- gnome-session-classic")
        })
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.GNOME_CLASSIC_XORG
            )
        )
    }
}
