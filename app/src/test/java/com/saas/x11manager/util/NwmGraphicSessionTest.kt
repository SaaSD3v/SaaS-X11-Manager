package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NwmGraphicSessionTest {

    @Test
    fun aptPlanUsesCurrentDebianNwmPackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.NWM
        )

        assertNotNull(plan)
        assertEquals(listOf("nwm"), plan?.packages)
        assertEquals("nwm", GraphicSession.NWM.startCommand)
        assertEquals("nwm", plan?.verificationCommand)
        assertNotNull(GraphicSessionSupport.specFor(GraphicSession.NWM))
    }

    @Test
    fun alpinePlanIsNotOfferedWithoutVerifiedPackage() {
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.NWM
            )
        )
    }
}
