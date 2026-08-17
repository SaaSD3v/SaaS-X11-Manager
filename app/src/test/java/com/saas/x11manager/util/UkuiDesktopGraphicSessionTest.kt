package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UkuiDesktopGraphicSessionTest {

    @Test
    fun aptPlanExplicitlyInstallsCoreUkuiSessionComponents() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.UKUI_DESKTOP
        )

        assertNotNull(plan)
        assertEquals(
            listOf("ukui-session-manager", "ukwm", "ukui-panel", "ukui-settings-daemon", "dbus-x11", "xterm"),
            plan?.packages
        )
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
