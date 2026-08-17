package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnlightenmentAlpineGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialEnlightenmentPackageAndDbus() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.ENLIGHTENMENT
        )

        assertNotNull(plan)
        assertEquals(listOf("enlightenment", "dbus", "xterm"), plan?.packages)
        assertEquals("saas-enlightenment-session", GraphicSession.ENLIGHTENMENT.startCommand)
    }

    @Test
    fun launcherSupportsDebianAndAlpineExecutableNames() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.ENLIGHTENMENT))
        val command = spec.postInstallCommands.joinToString("\n") { it.command }

        assertTrue(command.contains("dbus-run-session -- enlightenment_start"))
        assertTrue(command.contains("dbus-run-session -- enlightenment"))
        assertTrue(spec.verificationCommands.any {
            it.command.contains("enlightenment_start") && it.command.contains("enlightenment")
        })
    }
}
