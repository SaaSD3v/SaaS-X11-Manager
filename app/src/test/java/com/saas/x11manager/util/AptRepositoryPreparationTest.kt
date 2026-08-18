package com.saas.x11manager.util

import org.junit.Assert.assertTrue
import org.junit.Test

class AptRepositoryPreparationTest {

    @Test
    fun universePreparationChecksEveryPackageAndBothComponentSyntaxes() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.XFCE
            )
        )
        val step = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Checking required apt repository"
            }
        )

        assertTrue(step.command.contains("all_packages_available"))
        plan.packages.forEach { packageName ->
            assertTrue(step.command.contains(packageName))
        }
        assertTrue(step.command.contains("add-apt-repository --help"))
        assertTrue(step.command.contains("--component"))
        assertTrue(step.command.contains("add-apt-repository -y -c universe"))
        assertTrue(step.command.contains("add-apt-repository -y universe"))
    }

    @Test
    fun multiversePreparationAlsoSupportsModernAndLegacySyntax() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.AMIWM
            )
        )
        val step = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Checking required apt repository"
            }
        )

        plan.packages.forEach { packageName ->
            assertTrue(step.command.contains(packageName))
        }
        assertTrue(step.command.contains("add-apt-repository --help"))
        assertTrue(step.command.contains("add-apt-repository -y -c multiverse"))
        assertTrue(step.command.contains("add-apt-repository -y multiverse"))
    }
}
