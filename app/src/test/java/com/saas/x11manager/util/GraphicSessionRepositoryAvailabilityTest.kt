package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionRepositoryAvailabilityTest {

    @Test
    fun alpinePlansCheckRepositoryAvailabilityBeforeAtomicInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.TWM)
        )
        val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
        val installCommand = "apk add ${plan.packages.joinToString(" ")}"
        val installIndex = commands.indexOf(installCommand)

        assertTrue(installIndex >= 0)
        plan.packages.forEach { packageName ->
            val checkIndex = commands.indexOf("apk search -e $packageName >/dev/null")
            assertTrue(checkIndex >= 0)
            assertTrue(checkIndex < installIndex)
        }
    }

    @Test
    fun aptMultiversePlanUsesCapabilityBasedUbuntuFallback() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.AMIWM)
        )
        val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
        val repositoryStep = commands.first { it.contains("add-apt-repository") }

        assertTrue(repositoryStep.contains("all_packages_available"))
        assertTrue(repositoryStep.contains("for pkg in ${plan.packages.joinToString(" ")}"))
        assertTrue(repositoryStep.contains("apt-cache show \"\$pkg\""))
        plan.packages.forEach { packageName ->
            assertTrue(repositoryStep.contains(packageName))
        }
        assertTrue(repositoryStep.contains("^ID=ubuntu$"))
        assertTrue(repositoryStep.contains("add-apt-repository -y multiverse"))
        assertTrue(repositoryStep.contains("Multiverse/non-free"))
        assertFalse(repositoryStep.contains("VERSION_ID="))
    }
}
