package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FourthWindowManagerBatchTest {

    @Test
    fun fourthBatchUsesAptPlansAndNoUnverifiedAlpinePlans() {
        assertAptOnly(GraphicSession.BSPWM, listOf("bspwm", "sxhkd", "xterm"))
        assertAptOnly(GraphicSession.CLFSWM, listOf("clfswm", "xterm"))
        assertAptOnly(GraphicSession.FVWM_CRYSTAL, listOf("fvwm-crystal", "xterm"))
        assertAptOnly(GraphicSession.QTILE, listOf("qtile", "xterm"))
        assertAptOnly(GraphicSession.MUFFIN, listOf("muffin", "dbus-x11", "xterm"))
        assertAptOnly(GraphicSession.MUTTER, listOf("mutter", "dbus-x11", "xterm"))
        assertAptOnly(GraphicSession.UKWM, listOf("ukwm", "dbus-x11", "xterm"))
        assertAptOnly(GraphicSession.CINNAMON_SHELL, listOf("cinnamon", "dbus-x11", "xterm"))
        assertAptOnly(
            GraphicSession.COMPIZ,
            listOf("compiz-core", "compiz-plugins-default", "dbus-x11", "xterm")
        )
        assertAptOnly(GraphicSession.SUBTLE, listOf("subtle", "xterm"))
    }

    @Test
    fun bspwmAndQtileUseExplicitSessionWrappers() {
        val bspwm = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.BSPWM))
        val qtile = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.QTILE))

        assertTrue(bspwm.postInstallCommands.any { it.command.contains("sxhkd") && it.command.contains("exec bspwm") })
        assertTrue(qtile.postInstallCommands.any { it.command.contains("exec qtile start") })
        assertEquals("saas-bspwm-session", GraphicSession.BSPWM.startCommand)
        assertEquals("saas-qtile-session", GraphicSession.QTILE.startCommand)
    }

    @Test
    fun integratedManagersUseDbusRunSessionWrappers() {
        listOf(
            GraphicSession.MUFFIN,
            GraphicSession.MUTTER,
            GraphicSession.UKWM,
            GraphicSession.CINNAMON_SHELL,
            GraphicSession.COMPIZ
        ).forEach { session ->
            val spec = requireNotNull(GraphicSessionSupport.specFor(session))
            assertTrue(spec.postInstallCommands.any { it.command.contains("dbus-run-session") })
        }
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
