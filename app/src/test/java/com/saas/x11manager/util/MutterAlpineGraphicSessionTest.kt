package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutterAlpineGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialMutterPackageAndDbusLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.MUTTER
        )

        assertNotNull(plan)
        assertEquals(listOf("mutter", "dbus", "xterm"), plan?.packages)
        assertEquals("saas-mutter-session", GraphicSession.MUTTER.startCommand)

        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.MUTTER))
        assertTrue(spec.postInstallCommands.any {
            it.command.contains("dbus-run-session -- mutter")
        })
    }
}
