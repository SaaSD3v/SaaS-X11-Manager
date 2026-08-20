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
    fun sessionScriptRejectsMissingOrAmbiguousMountedSockets() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("Multiple X11 sockets are mounted"))
        assertTrue(script.contains("No X11 socket is mounted in /tmp/.X11-unix"))
        assertTrue(script.contains("Invalid X11 socket name"))
    }

    @Test
    fun sessionScriptSecuresXdgRuntimeDirectory() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("export XDG_RUNTIME_DIR=/tmp/runtime-root"))
        assertTrue(script.contains("mkdir -p \"\$XDG_RUNTIME_DIR\" && chmod 700 \"\$XDG_RUNTIME_DIR\""))
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
