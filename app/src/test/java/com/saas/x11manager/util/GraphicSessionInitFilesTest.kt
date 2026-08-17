package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionInitFilesTest {

    @Test
    fun openboxSessionScriptExecutesOnlyOpenbox() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.OPENBOX, "/bin/sh")

        assertTrue(script.contains("export DISPLAY=:0"))
        assertTrue(script.contains("exec openbox-session"))
        assertFalse(script.contains("startxfce4"))
        assertFalse(script.contains("startlxqt"))
    }

    @Test
    fun legacyXfceDefaultStillRendersXfceCommand() {
        val script = GraphicSessionInitFiles.sessionScript(GraphicSession.XFCE, "/bin/sh")
        assertTrue(script.contains("exec startxfce4"))
    }

    @Test
    fun openrcUsesGenericSessionServiceNameAndPidfile() {
        val setup = GraphicSessionInitFiles.openRcSetupService()
        val session = GraphicSessionInitFiles.openRcSessionService(GraphicSession.OPENBOX)

        assertTrue(setup.contains("before x11-session"))
        assertTrue(session.contains("/run/x11-session.pid"))
        assertTrue(session.contains("X11 Openbox Session"))
        assertFalse(session.contains("x11-xfce"))
    }

    @Test
    fun systemdUsesGenericSessionUnit() {
        val socket = GraphicSessionInitFiles.systemdSocketService()
        val session = GraphicSessionInitFiles.systemdSessionService(GraphicSession.OPENBOX)

        assertTrue(socket.contains("Before=x11-session.service"))
        assertTrue(session.contains("X11 Openbox Session"))
        assertTrue(session.contains("ExecStart=/usr/local/bin/x11-session.sh"))
        assertFalse(session.contains("x11-xfce"))
    }
}
