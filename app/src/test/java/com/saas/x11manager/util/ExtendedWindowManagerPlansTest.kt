package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedWindowManagerPlansTest {

    @Test
    fun firstBatchUsesVerifiedPackagePlans() {
        assertPlan(ContainerPlatform.UBUNTU, GraphicSession.TWM, listOf("twm", "xterm"))
        assertPlan(ContainerPlatform.ALPINE, GraphicSession.TWM, listOf("twm", "xterm"))

        assertPlan(ContainerPlatform.UBUNTU, GraphicSession.WINDOW_MAKER, listOf("wmaker", "xterm"))
        assertPlan(ContainerPlatform.ALPINE, GraphicSession.WINDOW_MAKER, listOf("windowmaker", "xterm"))

        assertAptOnly(GraphicSession.FVWM, listOf("fvwm", "xterm"))
        assertAptOnly(GraphicSession.PEKWM, listOf("pekwm", "xterm"))
        assertAptOnly(GraphicSession.BLACKBOX, listOf("blackbox", "xterm"))
        assertAptOnly(GraphicSession.CTWM, listOf("ctwm", "xterm"))
        assertAptOnly(GraphicSession.EVILWM, listOf("evilwm", "xterm"))
        assertAptOnly(GraphicSession.MATCHBOX, listOf("matchbox-window-manager", "xterm"))
        assertAptOnly(GraphicSession.SAWFISH, listOf("sawfish", "xterm"))
        assertAptOnly(GraphicSession.XMONAD, listOf("xmonad", "xterm"))
    }

    private fun assertAptOnly(session: GraphicSession, packages: List<String>) {
        assertPlan(ContainerPlatform.UBUNTU, session, packages)
        assertTrue(GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session) == null)
    }

    private fun assertPlan(
        platform: ContainerPlatform,
        session: GraphicSession,
        packages: List<String>
    ) {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(platform, session))
        assertEquals(packages, plan.packages)
        assertEquals(session.startCommand, plan.verificationCommand)
    }
}
