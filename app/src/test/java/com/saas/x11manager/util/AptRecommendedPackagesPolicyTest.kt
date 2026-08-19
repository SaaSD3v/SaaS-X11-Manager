package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AptRecommendedPackagesPolicyTest {

    @Test
    fun aptPlansSuppressRecommendedPackagesUnlessExplicitlyOptedIn() {
        GraphicSessionSupport.installableSessions.forEach { session ->
            val plan = GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
                ?: return@forEach

            assertFalse(plan.installRecommendedPackages)

            val installCommands = if (session in setOf(
                    GraphicSession.OPENBOX,
                    GraphicSession.ICEWM,
                    GraphicSession.JWM
                )
            ) {
                GraphicSessionInstaller.stepsFor(plan)
            } else {
                AdditionalGraphicSessionInstaller.stepsFor(plan)
            }
                .filter { it.command.contains("apt-get install -y") }
                .map { it.command }

            assertTrue(installCommands.isNotEmpty())
            installCommands.forEach { command ->
                assertTrue(command.contains("--no-install-recommends"))
                assertFalse(Regex("""(^|\s)--install-recommends(\s|$)""").containsMatchIn(command))
            }
        }
    }

    @Test
    fun explicitAptOverrideCanInstallRecommendedPackagesForOneOperation() {
        val session = GraphicSession.QTILE
        AptInstallRecommendationOverride.set(session, installRecommendedPackages = true)
        try {
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
            )
            assertTrue(plan.installRecommendedPackages)

            val installCommand = AdditionalGraphicSessionInstaller.stepsFor(plan)
                .single { it.command.contains("apt-get install -y") }
                .command
            assertTrue(
                Regex("""(^|\s)--install-recommends(\s|$)""").containsMatchIn(installCommand)
            )
            assertFalse(installCommand.contains("--no-install-recommends"))
        } finally {
            AptInstallRecommendationOverride.clear(session)
        }

        val defaultPlan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
        )
        assertFalse(defaultPlan.installRecommendedPackages)
    }

    @Test
    fun aptOverrideNeverChangesAlpinePackagePolicy() {
        val session = GraphicSession.OPENBOX
        AptInstallRecommendationOverride.set(session, installRecommendedPackages = false)
        try {
            val alpinePlan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
            )
            assertTrue(alpinePlan.installRecommendedPackages)
            val installCommand = GraphicSessionInstaller.stepsFor(alpinePlan)
                .single { it.command.startsWith("apk add ") }
                .command
            assertFalse(installCommand.contains("recommends"))
        } finally {
            AptInstallRecommendationOverride.clear(session)
        }
    }

    @Test
    fun desktopPlansKnownToRecommendAudioRemainSuppressed() {
        setOf(
            GraphicSession.LXQT,
            GraphicSession.MATE,
            GraphicSession.CINNAMON_SHELL,
            GraphicSession.CINNAMON_DESKTOP
        ).forEach { session ->
            val plan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
            )
            assertFalse(plan.installRecommendedPackages)
        }
    }
}
