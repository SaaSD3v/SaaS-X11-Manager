package com.saas.x11manager.util

import org.junit.Assert.assertEquals
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
    fun aptPackagesAreInstalledInOneRecommendedTransactionAfterIndividualPreflightChecks() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.XFCE
            )
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)

        plan.packages.forEach { packageName ->
            assertTrue(steps.any { it.command == "apt-cache show $packageName >/dev/null 2>&1" })
        }

        val installSteps = steps.filter { it.command.contains("apt-get install -y") }
        assertEquals(1, installSteps.size)
        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y --install-recommends ${plan.packages.joinToString(" ")}",
            installSteps.single().command
        )
    }
}
