package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionInstallerTest {

    private fun alpineOpenboxSteps(): List<GraphicSessionInstallStep> {
        val plan = requireNotNull(GraphicSessionInstallPlans.forSelection(
            ContainerPlatform.ALPINE,
            GraphicSession.OPENBOX
        ))
        return GraphicSessionInstaller.stepsFor(plan)
    }

    @Test
    fun openboxWorkflowLogsEachInstallAreaAsSeparateStep() {
        val steps = alpineOpenboxSteps()

        assertEquals(
            listOf(
                "Refreshing package index",
                "Installing Openbox",
                "Installing terminal",
                "Installing fonts",
                "Creating Openbox configuration directory",
                "Installing default Openbox rc.xml",
                "Installing default Openbox menu.xml",
                "Validating Openbox configuration",
                "Validating Openbox session command"
            ),
            steps.map { it.title }
        )
    }

    @Test
    fun openboxWorkflowUsesOnlyTheMinimalAlpinePackages() {
        val commands = alpineOpenboxSteps().map { it.command }

        assertTrue("apk update" in commands)
        assertTrue("apk add openbox" in commands)
        assertTrue("apk add xterm" in commands)
        assertTrue("apk add font-terminus" in commands)

        val joined = commands.joinToString("\n")
        listOf(
            "xorg-server",
            "xinit",
            "xauth",
            "mesa-egl",
            "mesa-gles",
            "mesa-dri-gallium",
            "py3-xdg",
            "pulseaudio"
        ).forEach { unnecessary ->
            assertFalse(joined.contains(unnecessary))
        }
    }

    @Test
    fun openboxConfigCopyDoesNotOverwriteExistingUserFiles() {
        val commands = alpineOpenboxSteps().map { it.command }

        assertTrue(commands.any {
            it.contains("[ -f /root/.config/openbox/rc.xml ] ||") &&
                it.contains("cp /etc/xdg/openbox/rc.xml")
        })
        assertTrue(commands.any {
            it.contains("[ -f /root/.config/openbox/menu.xml ] ||") &&
                it.contains("cp /etc/xdg/openbox/menu.xml")
        })
    }

    @Test
    fun installerValidatesButNeverLaunchesOpenbox() {
        val commands = alpineOpenboxSteps().map { it.command }

        assertTrue(commands.contains("command -v openbox-session"))
        assertFalse(commands.any { it.trim() == "openbox-session" })
        assertFalse(commands.any { it.contains("exec openbox-session") })
    }
}
