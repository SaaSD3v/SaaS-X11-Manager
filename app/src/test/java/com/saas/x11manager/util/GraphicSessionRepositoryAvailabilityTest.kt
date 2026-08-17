package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionRepositoryAvailabilityTest {

    @Test
    fun alpinePlansCheckRepositoryAvailabilityBeforeInstall() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.TWM)
        )
        val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }

        assertTrue(commands.indexOf("apk search -e twm >/dev/null") < commands.indexOf("apk add twm"))
        assertTrue(commands.indexOf("apk search -e xterm >/dev/null") < commands.indexOf("apk add xterm"))
    }

    @Test
    fun aptMultiversePlanUsesCapabilityBasedUbuntuFallback() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.AMIWM)
        )
        val commands = AdditionalGraphicSessionInstaller.stepsFor(plan).map { it.command }
        val repositoryStep = commands.first { it.contains("add-apt-repository") }

        assertTrue(repositoryStep.contains("apt-cache show amiwm"))
        assertTrue(repositoryStep.contains("^ID=ubuntu$"))
        assertTrue(repositoryStep.contains("add-apt-repository -y multiverse"))
        assertTrue(repositoryStep.contains("Multiverse/non-free"))
        assertFalse(repositoryStep.contains("VERSION_ID="))
    }
}
