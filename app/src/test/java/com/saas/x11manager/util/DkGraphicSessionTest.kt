package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DkGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialDkPackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.DK
        )

        assertNotNull(plan)
        assertEquals(listOf("dk", "xterm"), plan?.packages)
        assertEquals("dk", GraphicSession.DK.startCommand)
        assertEquals("dk", plan?.verificationCommand)
        assertNotNull(GraphicSessionSupport.specFor(GraphicSession.DK))
    }

    @Test
    fun aptPlanIsNotOfferedWithoutVerifiedPackage() {
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.DK
            )
        )
    }
}
