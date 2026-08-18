package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeClassicXorgGraphicSessionTest {

    @Test
    fun aptPlanUsesDistroNeutralClassicPackages() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNOME_CLASSIC_XORG
        )

        assertNotNull(plan)
        assertEquals(
            listOf("gnome-shell-extensions", "dbus-x11", "xterm"),
            plan?.packages
        )
        assertEquals("saas-gnome-classic-xorg-session", GraphicSession.GNOME_CLASSIC_XORG.startCommand)
    }

    @Test
    fun installerPreflightsActualClassicXorgProviderAndKeepsAllRecommends() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNOME_CLASSIC_XORG
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val preflight = requireNotNull(
            steps.firstOrNull { it.title == "Checking GNOME Classic Xorg package capability" }
        )
        val install = requireNotNull(
            steps.firstOrNull { it.title == "Installing GNOME Classic Xorg packages" }
        )

        assertTrue(preflight.command.contains("apt-cache show gnome-classic-xsession"))
        assertTrue(preflight.command.contains("candidate=gnome-classic-xsession"))
        assertTrue(preflight.command.contains("candidate=gnome-shell-extensions"))
        assertTrue(preflight.command.contains("usr/share/xsessions/gnome-classic-xorg.desktop"))
        assertTrue(preflight.command.contains("Wayland-only GNOME Classic support"))
        assertTrue(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("gnome-shell-extensions dbus-x11 xterm"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("\$pkg-"))
    }

    @Test
    fun wrapperUsesClassicSessionLauncher() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.GNOME_CLASSIC_XORG))
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
