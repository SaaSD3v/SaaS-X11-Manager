package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnomeFlashbackGraphicSessionTest {

    @Test
    fun aptPlanUsesFlashbackSessionPackage() {
        val plan = GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.UBUNTU,
            GraphicSession.GNOME_FLASHBACK
        )

        assertNotNull(plan)
        assertEquals(listOf("gnome-session-flashback", "dbus-x11", "xterm"), plan?.packages)
        assertEquals("saas-gnome-flashback-session", GraphicSession.GNOME_FLASHBACK.startCommand)
    }

    @Test
    fun aptInstallKeepsAllRecommendedPackagesWithoutSuppression() {
        val plan = requireNotNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.UBUNTU,
                GraphicSession.GNOME_FLASHBACK
            )
        )
        val install = requireNotNull(
            AdditionalGraphicSessionInstaller.stepsFor(plan).firstOrNull {
                it.title == "Installing GNOME Flashback packages"
            }
        )

        assertTrue(plan.installRecommendedPackages)
        assertTrue(install.command.contains("--install-recommends"))
        assertTrue(install.command.contains("gnome-session-flashback dbus-x11 xterm"))
        assertFalse(install.command.contains("blocked_recommends"))
        assertFalse(install.command.contains("\$pkg-"))
    }

    @Test
    fun wrapperStartsMetacityFlashbackSessionWithDesktopEnvironment() {
        val spec = requireNotNull(GraphicSessionSupport.specFor(GraphicSession.GNOME_FLASHBACK))
        val commands = spec.postInstallCommands.joinToString("\n") { it.command }
        assertTrue(commands.contains("DESKTOP_SESSION=gnome-flashback-metacity"))
        assertTrue(commands.contains("XDG_CURRENT_DESKTOP=GNOME-Flashback:GNOME"))
        assertTrue(commands.contains("gnome-session --session=gnome-flashback-metacity"))
        assertNull(
            GraphicSessionInstallPlans.forSelection(
                ContainerPlatform.ALPINE,
                GraphicSession.GNOME_FLASHBACK
            )
        )
    }
}
