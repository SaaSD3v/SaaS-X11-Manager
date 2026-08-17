package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MetacityAlpineGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialMetacityPackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.METACITY
        )

        assertNotNull(plan)
        assertEquals(listOf("metacity", "xterm"), plan?.packages)
        assertEquals("metacity", GraphicSession.METACITY.startCommand)
        assertEquals("metacity", plan?.verificationCommand)
        assertNotNull(GraphicSessionSupport.specFor(GraphicSession.METACITY))
    }
}
