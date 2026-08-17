package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MarcoAlpineGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialMarcoPackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.MARCO
        )

        assertNotNull(plan)
        assertEquals(listOf("marco", "xterm"), plan?.packages)
        assertEquals("marco", GraphicSession.MARCO.startCommand)
        assertEquals("marco", plan?.verificationCommand)
        assertNotNull(GraphicSessionSupport.specFor(GraphicSession.MARCO))
    }
}
