package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdWindowManagerBatchTest {

    @Test
    fun thirdBatchUsesAptOnlyArmPlans() {
        assertAptOnly(GraphicSession.WINDOWLAB, listOf("windowlab", "xterm"))
        assertAptOnly(GraphicSession.WM2, listOf("wm2", "xterm"))
        assertAptOnly(GraphicSession.STUMPWM, listOf("stumpwm", "xterm"))
        assertAptOnly(GraphicSession.NOTION, listOf("notion", "xterm"))
        assertAptOnly(GraphicSession.MWM, listOf("mwm", "xterm"))
        assertAptOnly(GraphicSession.MARCO, listOf("marco", "xterm"))
        assertAptOnly(GraphicSession.METACITY, listOf("metacity", "xterm"))
        assertAptOnly(GraphicSession.XFWM4, listOf("xfwm4", "xterm"))
        assertAptOnly(GraphicSession.KWIN_X11, listOf("dbus-x11", "kwin-x11", "xterm"))
        assertAptOnly(GraphicSession.ENLIGHTENMENT, listOf("dbus-x11", "enlightenment", "xterm"))
    }

    @Test
    fun integratedWindowManagersUseDedicatedDbusLaunchers() {
        val kwin = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.KWIN_X11))
        val enlightenment = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.ENLIGHTENMENT))

        assertTrue(kwin.postInstallCommands.any { it.command.contains("dbus-run-session -- kwin_x11") })
        assertTrue(enlightenment.postInstallCommands.any { it.command.contains("dbus-run-session -- enlightenment_start") })
        assertEquals("saas-kwin-x11-session", GraphicSession.KWIN_X11.startCommand)
        assertEquals("saas-enlightenment-session", GraphicSession.ENLIGHTENMENT.startCommand)
    }

    private fun assertAptOnly(session: GraphicSession, packages: List<String>) {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
        )
        assertEquals(packages, plan.packages)
        assertEquals(session.startCommand, plan.verificationCommand)
        assertTrue(GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session) == null)
    }
}
