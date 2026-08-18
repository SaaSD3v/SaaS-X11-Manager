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
    fun candidatePackagesAreInspectedForX11BeforeInstallation() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNOME_KIOSK_X11
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val preflightIndex = steps.indexOfFirst {
            it.title == "Checking GNOME Kiosk X11 package capability"
        }
        val installIndex = steps.indexOfFirst {
            it.title == "Installing GNOME Kiosk X11 packages"
        }

        assertTrue(preflightIndex >= 0)
        assertTrue(installIndex > preflightIndex)

        val command = steps[preflightIndex].command
        assertTrue(command.contains("apt-get download gnome-kiosk-script-session gnome-kiosk"))
        assertTrue(command.contains("dpkg-deb -c"))
        assertTrue(command.contains("gnome-kiosk-script-xorg.desktop"))
        assertTrue(command.contains("gnome-kiosk-script.session"))
        assertTrue(command.contains("org.gnome.Kiosk@x11.service"))
        assertTrue(command.contains("Wayland-only kiosk support"))
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
