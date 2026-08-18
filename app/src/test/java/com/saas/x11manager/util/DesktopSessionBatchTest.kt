package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSessionBatchTest {

    @Test
    fun mateUsesBothVerifiedPackageFamilies() {
        assertPlan(
            ContainerPlatform.UBUNTU,
            GraphicSession.MATE,
            listOf("mate-desktop-environment", "dbus-x11")
        )
        assertPlan(
            ContainerPlatform.ALPINE,
            GraphicSession.MATE,
            listOf("mate-desktop-environment", "dbus")
        )
    }

    @Test
    fun mateSuppressesRecommendedDesktopExtrasByDefault() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.MATE)
        )
        val install = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Installing MATE packages"
            }
        )

        assertFalse(plan.installRecommendedPackages)
        assertTrue(install.command.contains("--no-install-recommends"))
        assertTrue(install.command.contains("mate-desktop-environment dbus-x11"))
        assertFalse(install.command.contains("--install-recommends"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("\$pkg-"))
    }

    @Test
    fun researchedDesktopSessionsStayAptOnlyUntilAlpineIsConfirmed() {
        assertAptOnly(GraphicSession.LXDE, listOf("lxde-core", "openbox-lxde-session", "dbus-x11", "xterm"))
        assertAptOnly(
            GraphicSession.PLASMA_X11,
            listOf("plasma-desktop", "plasma-workspace", "kwin-x11", "dbus-x11", "xterm")
        )
        assertAptOnly(
            GraphicSession.CINNAMON_DESKTOP,
            listOf("cinnamon-session", "cinnamon", "muffin", "nemo", "cinnamon-settings-daemon", "dbus-x11", "xterm")
        )
        assertAptOnly(GraphicSession.SUGAR, listOf("sugar-session", "dbus-x11", "xterm"))
        assertAptOnly(GraphicSession.BUDGIE, listOf("budgie-desktop", "dbus-x11", "xterm"))
        assertAptOnly(GraphicSession.FVWM3, listOf("fvwm3", "xterm"))
    }

    @Test
    fun plasmaUsesDesktopComponentsWithoutDisplayManagerMetaPackage() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.PLASMA_X11)
        )

        assertTrue("plasma-desktop" in plan.packages)
        assertTrue("plasma-workspace" in plan.packages)
        assertTrue("kwin-x11" in plan.packages)
        assertTrue("kde-plasma-desktop" !in plan.packages)
        assertFalse(plan.installRecommendedPackages)
    }

    @Test
    fun cinnamonDesktopSuppressesAptRecommendsByDefault() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.CINNAMON_DESKTOP
            )
        )
        val install = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Installing Cinnamon Desktop packages"
            }
        )

        assertFalse(plan.installRecommendedPackages)
        assertTrue(install.command.contains("--no-install-recommends"))
        assertTrue(install.command.contains("cinnamon-session cinnamon muffin nemo cinnamon-settings-daemon dbus-x11 xterm"))
        assertFalse(install.command.contains("--install-recommends"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("\$pkg-"))
    }

    @Test
    fun budgieUsesFullDesktopPackageAndPreflightsRealX11Capability() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.BUDGIE)
        )
        val steps = AdditionalGraphicSessionInstaller.stepsFor(plan)
        val preflight = requireNotNull(
            steps.firstOrNull { it.title == "Checking Budgie X11 package capability" }
        )
        val install = requireNotNull(
            steps.firstOrNull { it.title == "Installing Budgie packages" }
        )

        assertEquals(listOf("budgie-desktop", "dbus-x11", "xterm"), plan.packages)
        assertFalse(plan.installRecommendedPackages)
        assertTrue(preflight.command.contains("apt-get download budgie-core"))
        assertTrue(preflight.command.contains("usr/share/xsessions/budgie-desktop.desktop"))
        assertTrue(preflight.command.contains("usr/bin/budgie-wm"))
        assertTrue(preflight.command.contains("Wayland-only Budgie support"))
        assertTrue(install.command.contains("--no-install-recommends"))
        assertFalse(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("budgie-desktop dbus-x11 xterm"))
    }

    @Test
    fun desktopSessionsUseExplicitLaunchersWithoutDisplayManagers() {
        val expected = mapOf(
            GraphicSession.MATE to "mate-session",
            GraphicSession.LXDE to "startlxde",
            GraphicSession.PLASMA_X11 to "startplasma-x11",
            GraphicSession.CINNAMON_DESKTOP to "cinnamon-session",
            GraphicSession.SUGAR to "sugar",
            GraphicSession.BUDGIE to "budgie-session"
        )

        expected.forEach { (session, executable) ->
            val spec = requireNotNull(GraphicSessionSupport.specFor(session))
            assertTrue(spec.postInstallCommands.any {
                it.command.contains("dbus-run-session -- $executable")
            })
        }

        ContainerPlatform.entries.forEach { platform ->
            GraphicSession.entries.forEach { session ->
                GraphicSessionInstallPlans.forSelection(platform, session)?.let { plan ->
                    assertTrue(
                        plan.packages.none {
                            it in setOf("gdm3", "lightdm", "sddm", "lxdm", "xserver-xorg")
                        }
                    )
                }
            }
        }
    }

    private fun assertAptOnly(session: GraphicSession, packages: List<String>) {
        assertPlan(ContainerPlatform.UBUNTU, session, packages)
        assertTrue(GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, session) == null)
    }

    private fun assertPlan(
        platform: ContainerPlatform,
        session: GraphicSession,
        packages: List<String>
    ) {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(platform, session))
        assertEquals(packages, plan.packages)
        assertEquals(session.startCommand, plan.verificationCommand)
    }
}
