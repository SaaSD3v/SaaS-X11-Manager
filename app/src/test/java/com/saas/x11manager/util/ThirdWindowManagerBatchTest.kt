package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdWindowManagerBatchTest {

    @Test
    fun thirdBatchUsesVerifiedArmPlans() {
        assertAptOnly(GraphicSession.WINDOWLAB, listOf("windowlab", "xterm"))
        assertAptOnly(GraphicSession.WM2, listOf("wm2", "xterm"))
        assertAptOnly(GraphicSession.STUMPWM, listOf("stumpwm", "xterm"))
        assertAptOnly(GraphicSession.NOTION, listOf("notion", "xterm"))
        assertAptOnly(GraphicSession.MWM, listOf("mwm", "xterm"))

        assertPlan(ContainerPlatform.UBUNTU, GraphicSession.MARCO, listOf("marco", "xterm"))
        assertPlan(ContainerPlatform.ALPINE, GraphicSession.MARCO, listOf("marco", "xterm"))
        assertPlan(ContainerPlatform.UBUNTU, GraphicSession.METACITY, listOf("metacity", "xterm"))
        assertPlan(ContainerPlatform.ALPINE, GraphicSession.METACITY, listOf("metacity", "xterm"))
        assertPlan(ContainerPlatform.UBUNTU, GraphicSession.XFWM4, listOf("xfwm4", "xterm"))
        assertPlan(ContainerPlatform.ALPINE, GraphicSession.XFWM4, listOf("xfwm4", "xterm"))

        assertAptOnly(GraphicSession.KWIN_X11, listOf("kwin-x11", "dbus-x11", "xterm"))
        assertPlan(
            ContainerPlatform.UBUNTU,
            GraphicSession.ENLIGHTENMENT,
            listOf("enlightenment", "dbus-x11", "xterm")
        )
        assertPlan(
            ContainerPlatform.ALPINE,
            GraphicSession.ENLIGHTENMENT,
            listOf("enlightenment", "dbus", "xterm")
        )
    }

    @Test
    fun integratedWindowManagersUseDedicatedDbusLaunchers() {
        val kwin = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.KWIN_X11))
        val enlightenment = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.ENLIGHTENMENT))

        assertTrue(kwin.postInstallCommands.any { it.command.contains("dbus-run-session -- kwin_x11") })
        assertTrue(enlightenment.postInstallCommands.any { it.command.contains("dbus-run-session -- enlightenment_start") })
        assertTrue(enlightenment.postInstallCommands.any { it.command.contains("dbus-run-session -- enlightenment") })
        assertEquals("saas-kwin-x11-session", GraphicSession.KWIN_X11.startCommand)
        assertEquals("saas-enlightenment-session", GraphicSession.ENLIGHTENMENT.startCommand)
    }

    private fun assertAptOnly(session: GraphicSession, packages: List<String>) {
        assertPlan(ContainerPlatform.UBUNTU, session, packages)
        assertTrue(GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session) == null)
    }

    private fun assertPlan(
        platform: ContainerPlatform,
        session: GraphicSession,
        packages: List<String>
    ) {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(platform, session))
        assertEquals(packages, plan.packages)
        assertEquals(session.startCommand, plan.verificationCommand)
    }
}
