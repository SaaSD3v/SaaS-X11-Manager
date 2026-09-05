package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionInitFilesTest {

    @Test
    fun openboxSessionScriptPinsDisplayZeroAndExecutesOnlyOpenbox() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("export DISPLAY=:0"))
        assertTrue(script.contains("export SAAS_HOST_DISPLAY=:0"))
        assertFalse(script.contains("/tmp/.X11-unix/X*"))
        assertFalse(script.contains("X11_DISPLAY_NUMBER"))
        assertFalse(script.contains("Multiple X11 sockets"))
        assertTrue(script.contains("exec openbox-session"))
        assertFalse(script.contains("startxfce4"))
        assertFalse(script.contains("startlxqt"))
    }

    @Test
    fun rootSessionScriptPreservesDirectLaunchOnDisplayZero() {
        val script = GraphicSessionInitFiles.rootSessionScript(GraphicSession.ICEWM, "/bin/sh")

        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue(script.contains("export DISPLAY=:0"))
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
        assertTrue(script.contains("useradd -m \"\$SESSION_USER\""))
        assertTrue(script.contains("export HOME=\$SESSION_HOME"))
        assertTrue(script.contains("export USER=\$SESSION_USER"))
        assertTrue(script.contains("export LOGNAME=\$SESSION_USER"))
        assertTrue(script.contains("exec su -p -s \"\$SESSION_SHELL\" \"\$SESSION_USER\""))
    }

    @Test
    fun noGraphicSessionStillPinsX0AndLaunchesNoDesktop() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.NONE, "/bin/sh")

        assertTrue(script.contains("export DISPLAY=:0"))
        assertTrue(script.endsWith("exit 0\n"))
        assertFalse(script.contains("exec openbox-session"))
        assertFalse(script.contains("exec startxfce4"))
    }

    @Test
    fun openrcUsesGenericSessionServiceNameAndPidfile() {
        val setup = GraphicSessionInitFiles.openRcSetupService()
        val session = GraphicSessionInitFiles.openRcSessionService(GraphicSession.OPENBOX)

        assertTrue(setup.contains("before x11-session"))
        assertTrue(setup.contains("chmod 700 /tmp/runtime-root"))
        assertTrue(session.contains("/run/x11-session.pid"))
        assertTrue(session.contains("X11 Openbox Session"))
        assertFalse(session.contains("x11-xfce"))
    }

    @Test
    fun systemdUsesSeparateGenericSessionUnit() {
        val socket = GraphicSessionInitFiles.systemdSocketService()
        val session = GraphicSessionInitFiles.systemdSessionService(GraphicSession.OPENBOX)

        assertTrue(socket.contains("Before=x11-session.service"))
        assertTrue(socket.contains("mount --bind /usr/.X11-unix /tmp/.X11-unix"))
        assertTrue(session.contains("ExecStart=/usr/local/bin/x11-session.sh"))
        assertFalse(session.contains("x11-xfce"))
    }
}
