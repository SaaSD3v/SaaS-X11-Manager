package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeXorgGraphicSessionTest {

    @Test
    fun aptPlanUsesDistroNeutralGnomeSessionPackages() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNOME_XORG
        )

        assertNotNull(plan)
        assertEquals(
            listOf("gnome-session", "dbus-x11", "xterm"),
            plan?.packages
        )
        assertEquals("saas-gnome-xorg-session", GraphicSession.GNOME_XORG.startCommand)
    }

    @Test
    fun installerPreflightsActualCandidateAndSuppressesRecommends() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNOME_XORG
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val preflight = requireNotNull(
            steps.firstOrNull { it.title == "Checking GNOME Xorg package capability" }
        )
        val install = requireNotNull(
            steps.firstOrNull { it.title == "Installing GNOME Xorg packages" }
        )

        assertTrue(preflight.command.contains("apt-cache policy 'gnome-session-xsession'"))
        assertTrue(preflight.command.contains("\$2 != \"(none)\""))
        assertFalse(preflight.command.contains("apt-cache show gnome-session-xsession"))
        assertTrue(preflight.command.contains("candidate=gnome-session-xsession"))
        assertTrue(preflight.command.contains("candidate=gnome-session"))
        assertTrue(preflight.command.contains("apt-get download"))
        assertTrue(preflight.command.contains("usr/share/xsessions/gnome-xorg.desktop"))
        assertTrue(preflight.command.contains("Wayland-only GNOME support"))
        assertFalse(plan.installRecommendedPackages)
        assertTrue(install.command.contains("--no-install-recommends"))
        assertFalse(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("gnome-session dbus-x11 xterm"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("\$pkg-"))
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
