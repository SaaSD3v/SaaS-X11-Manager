package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TwoBwmGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficial2bwmPackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.TWO_BWM
        )

        assertNotNull(plan)
        assertEquals(listOf("2bwm", "xterm"), plan?.packages)
        assertEquals("2bwm", GraphicSession.TWO_BWM.startCommand)
        assertEquals("2bwm", plan?.verificationCommand)
        assertNotNull(GraphicSessionSupport.specFor(GraphicSession.TWO_BWM))
    }

    @Test
    fun aptPlanIsNotOfferedWithoutVerifiedPackage() {
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.TWO_BWM
            )
        )
    }
}
