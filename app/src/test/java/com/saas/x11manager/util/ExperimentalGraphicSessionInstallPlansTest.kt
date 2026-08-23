package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperimentalGraphicSessionInstallPlansTest {

    @Test
    fun alpineQtileUsesTaggedTestingOnlyForQtilePackage() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.QTILE
            )
        )

        assertEquals(RepositoryRequirement.APK_EDGE_TESTING, plan.repositoryRequirement)
        assertEquals(listOf("qtile", "xterm"), plan.packages)
        assertEquals(setOf("qtile"), plan.repositoryPackages)
        assertEquals(
            listOf("qtile@$APK_EDGE_TESTING_TAG", "xterm"),
            plan.installPackageArguments()
        )
        assertEquals(GraphicSession.QTILE.startCommand, plan.verificationCommand)
    }
}
