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
    fun packageInstallerValidatesButNeverLaunchesOpenbox() {
        val commands = alpineOpenboxSteps().map { it.command }

        assertTrue(commands.contains("command -v openbox-session"))
        assertFalse(commands.any { it.trim() == "openbox-session" })
        assertFalse(commands.any { it.contains("exec openbox-session") })
    }

    @Test
    fun openrcStartupWritesGenericOpenboxSessionFiles() {
        val steps = GraphicSessionInstaller.startupStepsFor(
            InitSystem.OPENRC,
            GraphicSession.OPENBOX
        )
        val commands = steps.map { it.command }
        val joined = commands.joinToString("\n")

        assertTrue(steps.first().command == "test -x /sbin/openrc-run")
        assertTrue(joined.contains("/etc/init.d/x11-session"))
        assertTrue(joined.contains("/etc/runlevels/default/x11-session"))
        assertTrue(joined.contains("exec openbox-session"))
        assertTrue(joined.contains("/run/x11-session.pid"))
        assertFalse(commands.any { it.trim() == "openbox-session" })
    }

    @Test
    fun systemdStartupUsesGenericOpenboxUnitAndEnsuresBash() {
        val steps = GraphicSessionInstaller.startupStepsFor(
            InitSystem.SYSTEMD,
            GraphicSession.OPENBOX
        )
        val commands = steps.map { it.command }
        val joined = commands.joinToString("\n")

        assertTrue(commands.contains("command -v systemctl"))
        assertTrue(commands.contains("apk add bash"))
        assertTrue(joined.contains("/etc/systemd/system/x11-session.service"))
        assertTrue(joined.contains("graphical.target.wants/x11-session.service"))
        assertTrue(joined.contains("exec openbox-session"))
        assertFalse(commands.any { it.trim() == "openbox-session" })
    }

    @Test
    fun startupMigrationRemovesLegacyXfceServiceNames() {
        val openrc = GraphicSessionInstaller.startupStepsFor(
            InitSystem.OPENRC,
            GraphicSession.OPENBOX
        ).joinToString("\n") { it.command }
        val systemd = GraphicSessionInstaller.startupStepsFor(
            InitSystem.SYSTEMD,
            GraphicSession.OPENBOX
        ).joinToString("\n") { it.command }

        assertTrue(openrc.contains("rm -f") && openrc.contains("x11-xfce"))
        assertTrue(systemd.contains("rm -f") && systemd.contains("x11-xfce"))
    }
}
