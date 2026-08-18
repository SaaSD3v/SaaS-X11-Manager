package com.saas.x11manager.util

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

        assertTrue(alpine.packages.containsAll(listOf("dbus", "dbus-x11", "xfce4", "xfce4-terminal")))
        assertTrue(deb.packages.contains("xfce4-session"))
        assertTrue(deb.packages.contains("xfwm4"))
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
