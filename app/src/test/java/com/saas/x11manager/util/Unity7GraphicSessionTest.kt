package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Unity7GraphicSessionTest {

    @Test
    fun aptPlanUsesUbuntuUnitySessionPackage() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.UNITY7
        )

        assertNotNull(plan)
        assertEquals(listOf("unity-session", "dbus-x11", "xterm"), plan?.packages)
        assertEquals("saas-unity7-session", GraphicSession.UNITY7.startCommand)
    }

    @Test
    fun wrapperHandlesOldAndNewUnitySessionLaunchers() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.UNITY7))
        val command = spec.postInstallCommands.joinToString("\n") { it.command }

        assertTrue(command.contains("DESKTOP_SESSION=unity"))
        assertTrue(command.contains("command -v unity-session"))
        assertTrue(command.contains("dbus-run-session -- unity-session"))
        assertTrue(command.contains("gnome-session --session=unity"))
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.UNITY7
            )
        )
    }
}
