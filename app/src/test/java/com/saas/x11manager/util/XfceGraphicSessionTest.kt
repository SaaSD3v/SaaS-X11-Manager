package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XfceGraphicSessionTest {
    @Test
    fun existingPlansAreEnabledForGenericInstaller() {
        val alpine = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.XFCE)
        )
        val deb = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.XFCE)
        )

        assertTrue(
            alpine.packages.containsAll(
                listOf("dbus", "dbus-x11", "xfce4", "xfce4-terminal", "xfce4-notifyd")
            )
        )
        assertTrue(
            deb.packages.containsAll(
                listOf(
                    "xfce4-session",
                    "xfwm4",
                    "thunar-volman",
                    "xfce4-notifyd",
                    "xfce4-power-manager"
                )
            )
        )
        assertTrue(GraphicSessionSupport.specFor(GraphicSession.XFCE) != null)

        val alpineCommands = AdditionalGraphicSessionInstaller.stepsFor(alpine).map { it.command }
        val debCommands = AdditionalGraphicSessionInstaller.stepsFor(deb).map { it.command }
        assertTrue(alpineCommands.contains("command -v startxfce4"))
        assertTrue(debCommands.contains("command -v startxfce4"))
        assertTrue(debCommands.any { it.contains("--install-recommends") && it.contains("xfce4-session") })
        assertFalse(alpineCommands.any { it.trim() == "startxfce4" })
        assertFalse(debCommands.any { it.trim() == "startxfce4" })
    }

    @Test
    fun aptPlanKeepsRecommendedDesktopFeaturesWithoutInstallingSystemdSysv() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.XFCE)
        )
        val install = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Installing XFCE packages"
            }
        )

        assertTrue(plan.installRecommendedPackages)
        assertEquals(
            listOf("systemd-sysv"),
            GraphicSessionAptPolicy.blockedRecommendedPackages(GraphicSession.XFCE)
        )
        assertFalse("xorg" in plan.packages)
        assertFalse("xfce4-pulseaudio-plugin" in plan.packages)
        assertTrue(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("systemd-sysv"))
        assertTrue(install.command.contains("\$pkg-"))
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.XFCE
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec startxfce4"))
        }
    }
}
