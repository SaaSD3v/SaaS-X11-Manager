package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineInstallProfilePolicyTest {

    @Test
    fun canonicalAlpinePlansStayIndependentFromSharedInstallBase() {
        GraphicSessionSupport.installableSessions.forEach { session ->
            val plan = GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
                ?: return@forEach

            assertEquals(plan.packages.distinct(), plan.packages)
        }
    }

    @Test
    fun minimalProfileAddsSharedBaseWithoutDroppingSessionPackages() {
        GraphicSessionSupport.installableSessions.forEach { session ->
            val baseline = GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
                ?: return@forEach

            AlpineInstallProfileOverride.set(session, AlpineInstallProfile.MINIMAL)
            try {
                val minimal = requireNotNull(
                    GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
                )
                assertTrue(minimal.packages.containsAll(baseline.packages))
                assertTrue(minimal.packages.containsAll(AlpineInstallProfileOverride.baseX11Packages))
                assertEquals(minimal.packages.distinct(), minimal.packages)
            } finally {
                AlpineInstallProfileOverride.clear(session)
            }
        }
    }

    @Test
    fun fullProfileAddsSharedBaseAndDesktopIntegrationWithoutReplacingSessionPackages() {
        GraphicSessionSupport.installableSessions.forEach { session ->
            val baseline = GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
                ?: return@forEach

            AlpineInstallProfileOverride.set(session, AlpineInstallProfile.FULL)
            try {
                val full = requireNotNull(
                    GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
                )
                assertTrue(full.packages.containsAll(baseline.packages))
                assertTrue(full.packages.containsAll(AlpineInstallProfileOverride.baseX11Packages))
                assertTrue(full.packages.containsAll(AlpineInstallProfileOverride.fullDesktopPackages))
                assertEquals(full.packages.distinct(), full.packages)
            } finally {
                AlpineInstallProfileOverride.clear(session)
            }
        }
    }

    @Test
    fun sharedBaseAndFullProfileReachLegacyAndGenericApkInstallCommands() {
        listOf(
            GraphicSession.OPENBOX to true,
            GraphicSession.XFCE to false
        ).forEach { (session, legacy) ->
            AlpineInstallProfileOverride.set(session, AlpineInstallProfile.FULL)
            try {
                val plan = requireNotNull(
                    GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session)
                )
                val steps = if (legacy) {
                    GraphicSessionInstaller.stepsFor(plan)
                } else {
                    AdditionalGraphicSessionInstaller.stepsFor(plan)
                }
                assertTrue(steps.any { it.command == "apk update" })
                val installCommand = steps.single { it.command.startsWith("apk add ") }.command
                (AlpineInstallProfileOverride.baseX11Packages +
                    AlpineInstallProfileOverride.fullDesktopPackages).forEach { packageName ->
                    assertTrue(installCommand.split(' ').contains(packageName))
                }
            } finally {
                AlpineInstallProfileOverride.clear(session)
            }
        }
    }

    @Test
    fun alpineProfileNeverChangesAptPlans() {
        val session = GraphicSession.OPENBOX
        val baseline = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
        )

        AlpineInstallProfileOverride.set(session, AlpineInstallProfile.FULL)
        try {
            val aptPlan = requireNotNull(
                GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
            )
            assertEquals(baseline, aptPlan)
        } finally {
            AlpineInstallProfileOverride.clear(session)
        }
    }

    @Test
    fun alpineBaseAndFullDesktopBundleDoNotContainHostOwnedInfrastructure() {
        val blocked = setOf(
            "xorg-server",
            "lightdm",
            "sddm",
            "gdm",
            "lxdm",
            "xdm",
            "slim",
            "nodm",
            "pulseaudio",
            "pipewire-pulse"
        )

        assertFalse(
            (AlpineInstallProfileOverride.baseX11Packages +
                AlpineInstallProfileOverride.fullDesktopPackages).any(blocked::contains)
        )
    }
}
