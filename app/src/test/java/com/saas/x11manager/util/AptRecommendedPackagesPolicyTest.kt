package com.saas.x11manager.util

import org.junit.Assert.assertTrue
import org.junit.Test

class AptRecommendedPackagesPolicyTest {

    @Test
    fun allAptPlansInstallRecommendedPackagesExplicitly() {
        GraphicSessionSupport.installableSessions.forEach { session ->
            val plan = GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
                ?: return@forEach

            assertTrue(plan.installRecommendedPackages)

            if (session !in setOf(GraphicSession.OPENBOX, GraphicSession.ICEWM, GraphicSession.JWM)) {
                val installCommands = AdditionalGraphicSessionInstaller.stepsFor(plan)
                    .filter { it.title.startsWith("Installing ") }
                    .map { it.command }

                assertTrue(installCommands.isNotEmpty())
                installCommands.forEach { command ->
                    assertTrue(command.contains("--install-recommends"))
                    assertTrue(!command.contains("--no-install-recommends"))
                }
            }
        }
    }
}
