package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwmAlpineGraphicSessionTest {

    @Test
    fun alpineDwmUsesStableCommunityPlan() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.DWM
            )
        )

        // Repository classification is capability-based; no Alpine release is pinned by the app.
        assertEquals(RepositoryRequirement.APK_COMMUNITY, plan.repositoryRequirement)
        assertEquals(listOf("dwm", "xterm"), plan.packages)
        assertEquals(GraphicSession.DWM.startCommand, plan.verificationCommand)
        assertTrue(GraphicSession.DWM in GraphicSessionWizard.sessionsFor(ContainerPlatform.ALPINE))
        assertFalse(GraphicSessionWizard.isExperimental(ContainerPlatform.ALPINE, GraphicSession.DWM))
    }
}
