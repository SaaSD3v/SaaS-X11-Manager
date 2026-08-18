package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicGraphicPackageInstallTest {

    @Test
    fun alpinePackagesUseOneSafetySimulationBeforeAtomicInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.XFCE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)

        plan.packages.forEach { packageName ->
            assertFalse(steps.any { it.command == "apk search -e $packageName >/dev/null" })
        }

        val safetyIndex = steps.indexOfFirst { it.title == "Checking apk transaction safety" }
        val installSteps = steps.filter { it.command.startsWith("apk add ") }
        val installIndex = steps.indexOf(installSteps.single())
        assertTrue(safetyIndex >= 0)
        assertTrue(safetyIndex < installIndex)
        assertEquals(1, installSteps.size)
        assertEquals("apk add ${plan.packages.joinToString(" ")}", installSteps.single().command)
    }

    @Test
    fun aptPackagesUseBatchedCandidateResolutionAndSafetyBeforeAtomicInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.XFCE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)

        plan.packages.forEach { packageName ->
            assertFalse(steps.any { it.command == AptPackageAvailability.candidateCommand(packageName) })
        }

        val repositoryIndex = steps.indexOfFirst { it.title == "Checking required apt repository" }
        val safetyIndex = steps.indexOfFirst { it.title == "Checking APT transaction safety" }
        val installSteps = steps.filter { it.command.contains("apt-get install -y") }
        val installIndex = steps.indexOf(installSteps.single())

        assertTrue(repositoryIndex >= 0)
        assertTrue(safetyIndex > repositoryIndex)
        assertTrue(installIndex > safetyIndex)
        plan.packages.forEach { packageName ->
            assertTrue(steps[repositoryIndex].command.contains(packageName))
        }
        assertEquals(1, installSteps.size)
        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${plan.packages.joinToString(" ")}",
            installSteps.single().command
        )
        assertFalse(installSteps.single().command.contains("--install-recommends"))
    }
}
