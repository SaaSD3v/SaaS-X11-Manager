package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionRepositoryAvailabilityTest {

    @Test
    fun alpinePlansUseTransactionSimulationInsteadOfStandalonePackageChecks() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.TWM)
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val commands = steps.map { it.command }
        val installCommand = "apk add ${plan.packages.joinToString(" ")}"
        val installIndex = commands.indexOf(installCommand)
        val safetyIndex = steps.indexOfFirst { it.title == "Checking apk transaction safety" }

        assertTrue(installIndex >= 0)
        assertTrue(safetyIndex >= 0)
        assertTrue(safetyIndex < installIndex)
        plan.packages.forEach { packageName ->
            assertFalse(commands.contains("apk search -e $packageName >/dev/null"))
            assertTrue(steps[safetyIndex].command.contains(packageName))
        }
    }

    @Test
    fun aptPlansResolveAllCandidatesInOneRepositoryStepBeforeAtomicInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.TWM)
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val commands = steps.map { it.command }
        val installIndex = commands.indexOfFirst { it.contains("apt-get install -y") }
        val repositoryIndex = steps.indexOfFirst { it.title == "Checking required apt repository" }
        val safetyIndex = steps.indexOfFirst { it.title == "Checking APT transaction safety" }

        assertTrue(installIndex >= 0)
        assertTrue(repositoryIndex >= 0)
        assertTrue(safetyIndex > repositoryIndex)
        assertTrue(installIndex > safetyIndex)
        plan.packages.forEach { packageName ->
            assertFalse(commands.contains(AptPackageAvailability.candidateCommand(packageName)))
            assertTrue(steps[repositoryIndex].command.contains(packageName))
            assertTrue(steps[safetyIndex].command.contains(packageName))
        }
    }

    @Test
    fun aptMultiversePlanUsesCapabilityBasedUbuntuAndDebianFallbacks() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.AMIWM)
        )
        val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
        val repositoryStep = commands.first { it.contains("add-apt-repository") }

        assertTrue(repositoryStep.contains("apt_package_available()"))
        assertTrue(repositoryStep.contains("apt-cache policy \"\$1\""))
        assertTrue(repositoryStep.contains("Candidate:"))
        assertTrue(repositoryStep.contains("(none)"))
        assertTrue(repositoryStep.contains("all_packages_available"))
        assertTrue(repositoryStep.contains("for pkg in ${plan.packages.joinToString(" ")}"))
        assertTrue(repositoryStep.contains("apt_package_available \"\$pkg\""))
        assertFalse(repositoryStep.contains("apt-cache show"))
        plan.packages.forEach { packageName ->
            assertTrue(repositoryStep.contains(packageName))
        }

        assertTrue(repositoryStep.contains("^ID=ubuntu$"))
        assertTrue(repositoryStep.contains("add-apt-repository --help"))
        assertTrue(repositoryStep.contains("--component"))
        assertTrue(repositoryStep.contains("add-apt-repository -y -c multiverse"))
        assertTrue(repositoryStep.contains("add-apt-repository -y multiverse"))

        assertTrue(repositoryStep.contains("^ID=debian$"))
        assertTrue(repositoryStep.contains("saas-x11-manager-\$component"))
        assertTrue(repositoryStep.contains("component='non-free'"))
        assertTrue(repositoryStep.contains("*.sources"))
        assertTrue(repositoryStep.contains("*.list"))
        assertTrue(repositoryStep.contains("Required apt packages are still unavailable after enabling Debian non-free."))

        assertTrue(repositoryStep.contains("Multiverse/non-free"))
        assertFalse(repositoryStep.contains("VERSION_ID="))
        assertFalse(repositoryStep.contains("sed -i"))
    }
}
