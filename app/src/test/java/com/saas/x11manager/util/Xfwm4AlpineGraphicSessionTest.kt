package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Xfwm4AlpineGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialXfwm4PackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.XFWM4
        )

        assertNotNull(plan)
        assertEquals(listOf("xfwm4", "xterm"), plan?.packages)
        assertEquals("xfwm4", GraphicSession.XFWM4.startCommand)
        assertEquals("xfwm4", plan?.verificationCommand)
        assertNotNull(GraphicSessionSupport.specFor(GraphicSession.XFWM4))
    }
}
