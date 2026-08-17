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
                "Validating Alpine environment",
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
    fun openboxWorkflowValidatesAlpineBeforePackageChanges() {
        val steps = alpineOpenboxSteps()

        assertEquals(
            "test -f /etc/alpine-release && command -v apk",
            steps.first().command
        )
        assertEquals("apk update", steps[1].command)
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

    @Test
    fun openboxVerificationIsReadOnly() {
        InitSystem.entries.forEach { initSystem ->
            val commands = GraphicSessionInstaller.verificationStepsFor(
                ContainerPlatform.ALPINE,
                GraphicSession.OPENBOX,
                initSystem
            ).map { it.command }
            val joined = commands.joinToString("\n")

            assertFalse(joined.contains("apk update"))
            assertFalse(joined.contains("apk add "))
            assertFalse(joined.contains("rm -f"))
            assertFalse(joined.contains("ln -s"))
            assertFalse(joined.contains("chmod "))
            assertFalse(joined.contains("cp "))
            assertFalse(joined.contains("mkdir "))
        }
    }

    @Test
    fun openboxVerificationChecksPackagesConfigAndLauncher() {
        val commands = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.ALPINE,
            GraphicSession.OPENBOX,
            InitSystem.OPENRC
        ).map { it.command }
        val joined = commands.joinToString("\n")

        assertTrue(joined.contains("apk info -e openbox"))
        assertTrue(joined.contains("apk info -e xterm"))
        assertTrue(joined.contains("apk info -e font-terminus"))
        assertTrue(joined.contains("command -v openbox-session"))
        assertTrue(joined.contains("/root/.config/openbox/rc.xml"))
        assertTrue(joined.contains("/root/.config/openbox/menu.xml"))
        assertTrue(joined.contains("grep -Fqx 'exec openbox-session' /usr/local/bin/x11-session.sh"))
    }

    @Test
    fun openboxVerificationChecksSelectedInitWithoutStartingSession() {
        val openrc = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.ALPINE,
            GraphicSession.OPENBOX,
            InitSystem.OPENRC
        ).joinToString("\n") { it.command }
        val systemd = GraphicSessionInstaller.verificationStepsFor(
            ContainerPlatform.ALPINE,
            GraphicSession.OPENBOX,
            InitSystem.SYSTEMD
        ).joinToString("\n") { it.command }

        assertTrue(openrc.contains("/etc/init.d/x11-session"))
        assertTrue(openrc.contains("/etc/runlevels/default/x11-session"))
        assertTrue(systemd.contains("/etc/systemd/system/x11-session.service"))
        assertTrue(systemd.contains("graphical.target.wants/x11-session.service"))
        assertFalse(openrc.lines().any { it.trim() == "openbox-session" })
        assertFalse(systemd.lines().any { it.trim() == "openbox-session" })
    }
}
