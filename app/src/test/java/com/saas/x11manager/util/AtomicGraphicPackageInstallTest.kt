package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicGraphicPackageInstallTest {

    @Test
    fun alpinePackagesAreInstalledInOneTransactionAfterIndividualPreflightChecks() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.XFCE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)

        plan.packages.forEach { packageName ->
            assertTrue(steps.any { it.command == "apk search -e $packageName >/dev/null" })
        }

        val installSteps = steps.filter { it.command.startsWith("apk add ") }
        assertEquals(1, installSteps.size)
        assertEquals("apk add ${plan.packages.joinToString(" ")}", installSteps.single().command)
    }

    @Test
    fun aptPackagesAreInstalledInOneConservativeTransactionAfterIndividualPreflightChecks() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.XFCE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)

        plan.packages.forEach { packageName ->
            assertTrue(steps.any { it.command == AptPackageAvailability.candidateCommand(packageName) })
        }

        val installSteps = steps.filter { it.command.contains("apt-get install -y") }
        assertEquals(1, installSteps.size)
        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${plan.packages.joinToString(" ")}",
            installSteps.single().command
        )
        assertFalse(installSteps.single().command.contains("--install-recommends"))
    }
}
