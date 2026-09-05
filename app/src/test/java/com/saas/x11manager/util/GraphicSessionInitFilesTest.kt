package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionInitFilesTest {

    @Test
    fun openboxSessionScriptDiscoversMountedDisplayAndExecutesOnlyOpenbox() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("for candidate in /tmp/.X11-unix/X*"))
        assertTrue(script.contains("export DISPLAY=:\$X11_DISPLAY_NUMBER"))
        assertFalse(script.contains("export DISPLAY=:0"))
        assertTrue(script.contains("exec openbox-session"))
        assertFalse(script.contains("startxfce4"))
        assertFalse(script.contains("startlxqt"))
    }

    @Test
    fun rootSessionScriptPreservesLegacyDirectLaunchOnDynamicDisplay() {
        val script = GraphicSessionInitFiles.rootSessionScript(GraphicSession.ICEWM, "/bin/sh")

        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue(script.contains("for candidate in /tmp/.X11-unix/X*"))
        assertTrue(script.contains("export DISPLAY=:\$X11_DISPLAY_NUMBER"))
        assertTrue(script.contains("export HOME=/root"))
        assertTrue(script.contains("export USER=root"))
        assertTrue(script.contains("export LOGNAME=root"))
        assertTrue(script.contains("export SHELL=/bin/sh"))
        assertTrue(script.endsWith("exec icewm-session\n"))
        assertFalse(script.contains("SESSION_USER_FILE"))
        assertFalse(script.contains("exec su "))
    }

    @Test
    fun rootSessionScriptKeepsInitSpecificShellWithoutMixingOpenrcAndSystemd() {
        val openRc = GraphicSessionInitFiles.rootSessionScript(GraphicSession.ICEWM, "/bin/sh")
        val systemd = GraphicSessionInitFiles.rootSessionScript(GraphicSession.ICEWM, "/bin/bash")

        assertTrue(openRc.startsWith("#!/bin/sh\n"))
        assertTrue(openRc.contains("export SHELL=/bin/sh"))
        assertTrue(systemd.startsWith("#!/bin/bash\n"))
        assertTrue(systemd.contains("export SHELL=/bin/bash"))
    }

    @Test
    fun sessionScriptRejectsMissingOrAmbiguousMountedSockets() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("Multiple X11 sockets are mounted"))
        assertTrue(script.contains("No X11 socket is mounted in /tmp/.X11-unix"))
        assertTrue(script.contains("Invalid X11 socket name"))
    }

    @Test
    fun sessionScriptSecuresXdgRuntimeDirectoryForSelectedOwner() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("export XDG_RUNTIME_DIR=/tmp/runtime-root"))
        assertTrue(script.contains("mkdir -p \"\$XDG_RUNTIME_DIR\""))
        assertTrue(script.contains("chown \"\$SESSION_UID:\$SESSION_GID\" \"\$XDG_RUNTIME_DIR\""))
        assertTrue(script.contains("chmod 700 \"\$XDG_RUNTIME_DIR\""))
    }

    @Test
    fun sessionScriptDefaultsToRootButSupportsBasicUserCreationAndPrivilegeDrop() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("SESSION_USER=root"))
        assertTrue(script.contains("SESSION_USER_FILE=/etc/saas-x11-manager/session-user"))
        assertTrue(script.contains("[!A-Za-z_]*|*[!A-Za-z0-9_-]*"))
        assertTrue(script.contains("adduser -D \"\$SESSION_USER\""))
        assertTrue(script.contains("ADDUSER_NAME_OPT=--allow-bad-names"))
        assertTrue(script.contains("ADDUSER_NAME_OPT=--force-badname"))
        assertTrue(script.contains("adduser \$ADDUSER_NAME_OPT --disabled-password --comment '' \"\$SESSION_USER\""))
        assertTrue(script.contains("adduser \$ADDUSER_NAME_OPT --disabled-password --gecos '' \"\$SESSION_USER\""))
        assertTrue(script.contains("useradd -m \"\$SESSION_USER\""))
        assertTrue(script.contains("export HOME=\$SESSION_HOME"))
        assertTrue(script.contains("export USER=\$SESSION_USER"))
        assertTrue(script.contains("export LOGNAME=\$SESSION_USER"))
        assertTrue(script.contains("chown \"\$SESSION_UID:\$SESSION_GID\" \"\$SESSION_HOME\""))
        assertTrue(script.contains("exec su -p -s \"\$SESSION_SHELL\" \"\$SESSION_USER\""))
    }

    @Test
    fun legacyXfceDefaultStillRendersXfceCommand() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.XFCE, "/bin/sh")
        assertTrue(script.contains("exec startxfce4"))
    }

    @Test
    fun noGraphicSessionKeepsDynamicX11EnvironmentButLaunchesNoDesktop() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.NONE, "/bin/sh")

        assertTrue(script.contains("export DISPLAY=:\$X11_DISPLAY_NUMBER"))
        assertTrue(script.endsWith("exit 0\n"))
        assertFalse(script.contains("exec openbox-session"))
        assertFalse(script.contains("exec startxfce4"))
        assertFalse(script.contains("exec startlxqt"))
    }

    @Test
    fun openrcUsesGenericSessionServiceNameAndPidfile() {
        val setup = GraphicSessionInitFiles.openRcSetupService()
        val session = GraphicSessionInitFiles.openRcSessionService(GraphicSession.OPENBOX)

        assertTrue(setup.contains("before x11-session"))
        assertTrue(setup.contains("chmod 700 /tmp/runtime-root"))
        assertTrue(session.contains("/run/x11-session.pid"))
        assertTrue(session.contains("X11 Openbox Session"))
        assertTrue(session.contains("SaaS X11"))
        assertFalse(session.contains("x11-xfce"))
    }

    @Test
    fun systemdUsesGenericSessionUnitAndLeavesDisplayToLauncher() {
        val socket = GraphicSessionInitFiles.systemdSocketService()
        val session = GraphicSessionInitFiles.systemdSessionService(GraphicSession.OPENBOX)

        assertTrue(socket.contains("Before=x11-session.service"))
        assertTrue(socket.contains("test -d /usr/.X11-unix"))
        assertTrue(socket.contains("chmod 700 /tmp/runtime-root"))
        assertTrue(socket.contains("mountpoint -q /tmp/.X11-unix"))
        assertTrue(socket.contains("mount --bind /usr/.X11-unix /tmp/.X11-unix"))
        assertTrue(socket.contains("ExecStop=/bin/sh -c 'if mountpoint -q /tmp/.X11-unix"))
        assertTrue(socket.contains("umount /tmp/.X11-unix"))
        assertTrue(session.contains("X11 Openbox Session"))
        assertTrue(session.contains("ExecStart=/usr/local/bin/x11-session.sh"))
        assertFalse(session.contains("Environment=DISPLAY="))
        assertFalse(session.contains("x11-xfce"))
    }
}
