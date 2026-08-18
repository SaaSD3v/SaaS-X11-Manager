package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UkuiDesktopGraphicSessionTest {

    @Test
    fun aptPlanExplicitlyInstallsPortableUkuiDesktopComponents() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.UKUI_DESKTOP
        )

        assertNotNull(plan)
        assertEquals(
            listOf(
                "ukui-session-manager",
                "ukwm",
                "ukui-panel",
                "ukui-settings-daemon",
                "ukui-polkit",
                "ukui-menu",
                "ukui-notification-daemon",
                "ukui-sidebar",
                "peony",
                "dbus-x11",
                "xterm"
            ),
            plan?.packages
        )
        assertFalse(plan?.installRecommendedPackages == true)
        assertFalse(plan?.packages?.contains("ukui-greeter") == true)
        assertFalse(plan?.packages?.contains("ukui-power-manager") == true)
        assertFalse(plan?.packages?.contains("ukui-screensaver") == true)
        assertFalse(plan?.packages?.contains("ukui-desktop-environment-core") == true)
    }

    @Test
    fun completePlanPreflightsX11SessionAndAddsPortableComponents() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.UKUI_DESKTOP
            )
        )
        val preflight = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Checking UKUI X11 package capability"
            }
        )

        assertTrue("ukui-menu" in plan.packages)
        assertTrue("ukui-polkit" in plan.packages)
        assertTrue("ukui-notification-daemon" in plan.packages)
        assertTrue("ukui-sidebar" in plan.packages)
        assertTrue("peony" in plan.packages)
        assertTrue(preflight.command.contains("apt-get download ukui-session-manager"))
        assertTrue(preflight.command.contains("usr/bin/ukui-session"))
        assertTrue(preflight.command.contains("usr/share/xsessions/ukui.desktop"))
        assertFalse(preflight.command.contains("VERSION_ID="))
    }

    @Test
    fun wrapperUsesOfficialUkuiSessionLauncher() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.UKUI_DESKTOP))
        assertEquals("saas-ukui-session", GraphicSession.UKUI_DESKTOP.startCommand)
        assertTrue(spec.postInstallCommands.any { it.command.contains("dbus-run-session -- ukui-session") })
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.UKUI_DESKTOP
            )
        )
    }
}
