package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineEdgeTestingRepositoryPreparationTest {

    private fun experimentalQtilePlan() = GraphicSessionInstallPlan(
        platform = ContainerPlatform.ALPINE,
        session = GraphicSession.QTILE,
        repositoryRequirement = RepositoryRequirement.APK_EDGE_TESTING,
        packages = listOf("qtile"),
        verificationCommand = GraphicSession.QTILE.startCommand,
        installRecommendedPackages = true,
        repositoryPackages = setOf("qtile")
    )

    @Test
    fun edgeTestingRepositoryIsPreparedBeforePackageRefresh() {
        val steps = AdditionalGraphicSessionInstaller.stepsFor(experimentalQtilePlan())

        val repositoryIndex = steps.indexOfFirst {
            it.title == "Preparing Alpine edge/testing repository"
        }
        val refreshIndex = steps.indexOfFirst {
            it.title == "Refreshing package index"
        }

        assertTrue(repositoryIndex >= 0)
        assertTrue(refreshIndex > repositoryIndex)

        val command = steps[repositoryIndex].command
        assertTrue(command.contains("/etc/apk/repositories"))
        assertTrue(command.contains("@$APK_EDGE_TESTING_TAG"))
        assertTrue(command.contains("/edge/testing"))
        assertFalse(command.contains("/v3."))
    }

    @Test
    fun onlyRepositoryScopedPackagesReceiveTestingTag() {
        val plan = experimentalQtilePlan().copy(
            packages = listOf("qtile", "dbus-x11", "xdg-utils")
        )

        assertEquals(
            listOf("qtile@$APK_EDGE_TESTING_TAG", "dbus-x11", "xdg-utils"),
            plan.installPackageArguments()
        )

        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val safety = requireNotNull(steps.firstOrNull {
            it.title == "Checking apk transaction safety"
        })
        val install = requireNotNull(steps.firstOrNull {
            it.title == "Installing Qtile packages"
        })

        assertTrue(safety.command.contains("qtile@$APK_EDGE_TESTING_TAG dbus-x11 xdg-utils"))
        assertTrue(install.command.contains("apk add qtile@$APK_EDGE_TESTING_TAG dbus-x11 xdg-utils"))
    }

    @Test
    fun communityPackageArgumentsRemainUnchanged() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.FLUXBOX
            )
        )

        assertEquals(plan.packages, plan.installPackageArguments())
    }
}
