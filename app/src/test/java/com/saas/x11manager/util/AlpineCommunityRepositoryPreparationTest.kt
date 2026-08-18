package com.saas.x11manager.util

import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineCommunityRepositoryPreparationTest {

    @Test
    fun alpineCommunityIsPreparedBeforeRefreshingPackageIndex() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.FLUXBOX
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)

        val repositoryIndex = steps.indexOfFirst {
            it.title == "Preparing Alpine community repository"
        }
        val refreshIndex = steps.indexOfFirst {
            it.title == "Refreshing package index"
        }

        assertTrue(repositoryIndex >= 0)
        assertTrue(refreshIndex > repositoryIndex)

        val command = steps[repositoryIndex].command
        assertTrue(command.contains("setup-apkrepos -c"))
        assertTrue(command.contains("/etc/apk/repositories"))
        assertTrue(command.contains("main_repo"))
        assertTrue(command.contains("/community"))
    }
}
