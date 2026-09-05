package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionInstallPlansTest {

    @Test
    fun ubuntuXfceUsesExplicitCorePackagesWithoutAudioMeta() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.XFCE
        ))

        assertEquals(RepositoryRequirement.APT_UNIVERSE, plan.repositoryRequirement)
        assertEquals("startxfce4", plan.verificationCommand)
        assertFalse(plan.installRecommendedPackages)
        assertTrue(plan.packages.containsAll(listOf(
            "dbus-x11",
            "libxfce4ui-utils",
            "thunar",
            "xfce4-appfinder",
            "xfce4-panel",
            "xfce4-session",
            "xfce4-settings",
            "xfconf",
            "xfdesktop4",
            "xfwm4",
            "xfce4-terminal"
        )))
        assertSafeAptPlan(plan)
    }

    @Test
    fun ubuntuLxqtUsesCoreSessionAndOpenbox() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.LXQT
        ))

        assertEquals(RepositoryRequirement.APT_UNIVERSE, plan.repositoryRequirement)
        assertEquals("startlxqt", plan.verificationCommand)
        assertFalse(plan.installRecommendedPackages)
        assertEquals(
            listOf("dbus-x11", "lxqt-core", "openbox"),
            plan.packages
        )
        assertSafeAptPlan(plan)
    }

    @Test
    fun debOpenboxUsesDirectTermuxX11PackageSet() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.OPENBOX
        ))

        assertEquals(RepositoryRequirement.APT_UNIVERSE, plan.repositoryRequirement)
        assertEquals("openbox-session", plan.verificationCommand)
        assertEquals(
            listOf("openbox", "xterm", "fonts-terminus"),
            plan.packages
        )
        assertFalse(plan.installRecommendedPackages)
        assertSafeAptPlan(plan)

        val unnecessaryForDirectTermuxX11 = setOf(
            "xorg",
            "xorg-server",
            "xinit",
            "pulseaudio",
            "lightdm",
            "sddm",
            "gdm3"
        )
        assertTrue(plan.packages.none { it in unnecessaryForDirectTermuxX11 })
    }

    @Test
    fun debIcewmUsesDirectTermuxX11PackageSet() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.ICEWM
        ))

        assertEquals(RepositoryRequirement.APT_UNIVERSE, plan.repositoryRequirement)
        assertEquals("icewm-session", plan.verificationCommand)
        assertEquals(listOf("icewm", "xterm"), plan.packages)
        assertFalse(plan.installRecommendedPackages)
        assertSafeAptPlan(plan)
    }

    @Test
    fun debJwmUsesDirectTermuxX11PackageSet() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.JWM
        ))

        assertEquals(RepositoryRequirement.APT_UNIVERSE, plan.repositoryRequirement)
        assertEquals("jwm", plan.verificationCommand)
        assertEquals(listOf("jwm", "xterm"), plan.packages)
        assertFalse(plan.installRecommendedPackages)
        assertSafeAptPlan(plan)
    }

    @Test
    fun alpineXfceUsesCommunityDesktopAndDbus() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.XFCE
        ))

        assertEquals(RepositoryRequirement.APK_COMMUNITY, plan.repositoryRequirement)
        assertEquals("startxfce4", plan.verificationCommand)
        assertTrue(plan.installRecommendedPackages)
        assertTrue(plan.packages.containsAll(listOf(
            "dbus",
            "dbus-x11",
            "xfce4",
            "xfce4-terminal"
        )))
        assertNoDisplayManager(plan)
    }

    @Test
    fun alpineLxqtUsesCommunityDesktopAndDbus() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.LXQT
        ))

        assertEquals(RepositoryRequirement.APK_COMMUNITY, plan.repositoryRequirement)
        assertEquals("startlxqt", plan.verificationCommand)
        assertTrue(plan.installRecommendedPackages)
        assertEquals(listOf("dbus", "dbus-x11", "lxqt-desktop"), plan.packages)
        assertNoDisplayManager(plan)
    }

    @Test
    fun alpineOpenboxUsesMinimalTermuxX11PackageSet() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.OPENBOX
        ))

        assertEquals(RepositoryRequirement.APK_COMMUNITY, plan.repositoryRequirement)
        assertEquals("openbox-session", plan.verificationCommand)
        assertEquals(
            listOf("openbox", "xterm", "font-terminus"),
            plan.packages
        )
        assertTrue(plan.installRecommendedPackages)
        assertNoDisplayManager(plan)

        val unnecessaryForDirectTermuxX11 = setOf(
            "xorg-server",
            "xinit",
            "mesa-egl",
            "mesa-gles",
            "mesa-dri-gallium",
            "py3-xdg",
            "pulseaudio"
        )
        assertTrue(plan.packages.none { it in unnecessaryForDirectTermuxX11 })
    }

    @Test
    fun alpineIcewmUsesMinimalTermuxX11PackageSet() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.ICEWM
        ))

        assertEquals(RepositoryRequirement.APK_COMMUNITY, plan.repositoryRequirement)
        assertEquals("icewm-session", plan.verificationCommand)
        assertEquals(listOf("icewm", "xterm"), plan.packages)
        assertTrue(plan.installRecommendedPackages)
        assertNoDisplayManager(plan)
    }

    @Test
    fun alpineJwmUsesMinimalTermuxX11PackageSet() {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.JWM
        ))

        assertEquals(RepositoryRequirement.APK_COMMUNITY, plan.repositoryRequirement)
        assertEquals("jwm", plan.verificationCommand)
        assertEquals(listOf("jwm", "xterm"), plan.packages)
        assertTrue(plan.installRecommendedPackages)
        assertNoDisplayManager(plan)
    }

    @Test
    fun everyAptInstallScopedPlanIncludesSharedX11BaseDependencies() {
        val required = setOf("dbus", "dbus-x11", "x11-xserver-utils", "xauth")

        GraphicSession.entries.forEach { session ->
            val baseline = GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                session
            ) ?: return@forEach

            AptInstallRecommendationOverride.set(session, installRecommendedPackages = false)
            try {
                val installPlan = requireNotNull(
                    GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, session)
                )
                assertTrue(
                    "${session.name} is missing shared APT X11 base dependencies during install",
                    installPlan.packages.containsAll(required)
                )
                assertTrue(installPlan.packages.containsAll(baseline.packages))
            } finally {
                AptInstallRecommendationOverride.clear(session)
            }
        }
    }

    @Test
    fun noDesktopSelectionHasNoInstallPlan() {
        ContainerPlatform.entries.forEach { platform ->
            assertTrue(GraphicSessionInstallPlans.forSelection(platform, GraphicSession.NONE) == null)
        }
    }

    @Test
    fun everyImplementedPlanVerifiesTheSelectedSessionCommand() {
        ContainerPlatform.entries.forEach { platform ->
            GraphicSession.entries.forEach { session ->
                GraphicSessionInstallPlans.forSelection(platform, session)?.let { plan ->
                    assertEquals(session.startCommand, plan.verificationCommand)
                }
            }
        }
    }

    private fun assertSafeAptPlan(plan: GraphicSessionInstallPlan) {
        val forbidden = setOf(
            "xfce4",
            "xfce4-pulseaudio-plugin",
            "lxqt",
            "pavucontrol",
            "pavucontrol-qt",
            "pulseaudio",
            "lightdm",
            "sddm"
        )
        assertTrue(plan.packages.none { it in forbidden })
        assertNoDisplayManager(plan)
    }

    private fun assertNoDisplayManager(plan: GraphicSessionInstallPlan) {
        val displayManagers = setOf("lightdm", "lightdm-gtk-greeter", "sddm", "gdm3")
        assertTrue(plan.packages.none { it in displayManagers })
    }
}
