package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LxqtGraphicSessionTest {
    @Test
    fun existingPlansAreEnabledForGenericInstaller() {
        val alpine = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.ALPINE, GraphicSession.LXQT)
        )
        val deb = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.LXQT)
        )

        assertTrue(alpine.packages.containsAll(listOf("dbus", "dbus-x11", "lxqt-desktop")))
        assertTrue(deb.packages.containsAll(listOf("dbus-x11", "lxqt-core", "openbox")))
        assertTrue(GraphicSessionSupport.specFor(GraphicSession.LXQT) != null)

        val alpineCommands = AdditionalGraphicSessionInstaller.stepsFor(alpine).map { it.command }
        val debCommands = AdditionalGraphicSessionInstaller.stepsFor(deb).map { it.command }
        assertTrue(alpineCommands.contains("command -v startlxqt"))
        assertTrue(debCommands.contains("command -v startlxqt"))
        assertTrue(debCommands.any { it.contains("--no-install-recommends") && it.contains("lxqt-core") })
        assertFalse(alpineCommands.any { it.trim() == "startlxqt" })
        assertFalse(debCommands.any { it.trim() == "startlxqt" })
    }

    @Test
    fun aptCoreSuppressesRecommendedDesktopExtrasByDefault() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(ContainerPlatform.UBUNTU, GraphicSession.LXQT)
        )
        val install = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Installing LXQt packages"
            }
        )

        assertFalse(plan.installRecommendedPackages)
        assertTrue("lxqt-core" in plan.packages)
        assertTrue("openbox" in plan.packages)
        assertTrue(install.command.contains("--no-install-recommends"))
        assertFalse(install.command.contains("--install-recommends"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("\$pkg-"))
    }

    @Test
    fun startupUsesExistingGenericLauncher() {
        InitSystem.entries.forEach { initSystem ->
            val joined = GraphicSessionInstaller.startupStepsFor(
                ContainerPlatform.ALPINE,
                initSystem,
                GraphicSession.LXQT
            ).joinToString("\n") { it.command }
            assertTrue(joined.contains("exec startlxqt"))
        }
    }
}
