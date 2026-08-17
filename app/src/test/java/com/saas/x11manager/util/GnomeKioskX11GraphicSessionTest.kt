package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeKioskX11GraphicSessionTest {

    @Test
    fun aptPlanUsesKioskScriptSessionAndGnomeSessionBinary() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNOME_KIOSK_X11
        )

        assertNotNull(plan)
        assertEquals(
            listOf("gnome-kiosk-script-session", "gnome-session-bin", "dbus-x11"),
            plan?.packages
        )
        assertEquals("saas-gnome-kiosk-x11-session", GraphicSession.GNOME_KIOSK_X11.startCommand)
    }

    @Test
    fun supportRequiresRealXorgSessionFilesBeforeCreatingLauncher() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.GNOME_KIOSK_X11))
        val postInstall = spec.postInstallCommands.joinToString("\n") { it.command }

        assertTrue(postInstall.contains("gnome-kiosk-script-xorg.desktop"))
        assertTrue(postInstall.contains("gnome-kiosk-script.session"))
        assertTrue(postInstall.contains("gnome-session --session=gnome-kiosk-script"))
        assertTrue(postInstall.contains("Wayland-only kiosk support"))
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.GNOME_KIOSK_X11
            )
        )
    }
}
