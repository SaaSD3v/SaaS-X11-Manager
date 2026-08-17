package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BerryGraphicSessionTest {

    @Test
    fun alpinePlanUsesOfficialBerryPackageAndLauncher() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.BERRY
        )

        assertNotNull(plan)
        assertEquals(listOf("berry", "xterm"), plan?.packages)
        assertEquals("berry", GraphicSession.BERRY.startCommand)
        assertEquals("berry", plan?.verificationCommand)
    }

    @Test
    fun aptPlanIsNotOfferedWithoutVerifiedPackage() {
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.BERRY
            )
        )
    }
}
