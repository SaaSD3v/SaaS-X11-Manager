package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRcGraphicSessionRuntimePolicyTest {

    @Test
    fun openRcStartVerifiesRealX11ClientHandshakeBeforeSessionStart() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(1))

        assertTrue(command.contains("display=':1'"))
        assertTrue(command.contains("command -v xset"))
        assertTrue(command.contains("DISPLAY=\"\$display\" timeout 2 xset q"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=x11-client-ready"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=x11-client-not-ready"))
        assertTrue(command.indexOf("DISPLAY=\"\$display\" timeout 2 xset q") < command.indexOf("rc-service x11-session start"))
    }

    @Test
    fun openRcStartCapturesTheActualServiceStartResult() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(1))

        assertTrue(command.contains("start_output=\$(rc-service x11-session start 2>&1)"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=start-exit="))
        assertTrue(command.contains("__SAAS_X11_DIAG__=openrc start:"))
        assertTrue(command.contains("__SAAS_X11_DIAG__=service status:"))
    }

    @Test
    fun runtimeResetsOnlyConfirmedCrashedStateWithoutDeletingServiceFiles() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(1))
        val stop = GraphicSessionRuntimeController.buildStopCommand()

        assertTrue(command.contains("rc-service x11-session zap"))
        assertTrue(command.contains("session_status\" -eq 32"))
        assertTrue(command.contains("kill -0"))
        assertFalse(command.contains("rm -f /etc/runlevels/default/x11-session"))
        assertFalse(command.contains("rm -f /run/x11-session.pid"))
        assertFalse(stop.contains("rc-service x11-session zap"))
    }

    @Test
    fun systemdPathKeepsItsExistingFailureResetBehavior() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(0))

        assertTrue(command.contains("systemctl reset-failed x11-session.service"))
    }
}
