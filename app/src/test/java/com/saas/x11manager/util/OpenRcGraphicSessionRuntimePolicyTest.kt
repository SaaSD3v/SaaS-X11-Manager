package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRcGraphicSessionRuntimePolicyTest {

    @Test
    fun openRcStartVerifiesRealX0ClientHandshakeBeforeSessionStart() {
        val command = GraphicSessionRuntimeController.buildStartCommand()

        assertTrue(command.contains("display=':0'"))
        assertTrue(command.contains("expected='/tmp/.X11-unix/X0'"))
        assertTrue(command.contains("command -v xset"))
        assertTrue(command.contains("DISPLAY=\"\$display\" xset q"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=x11-client-ready"))
        assertTrue(command.indexOf("DISPLAY=\"\$display\" xset q") < command.indexOf("rc-service x11-session start"))
    }

    @Test
    fun openRcStartCapturesActualServiceStartResult() {
        val command = GraphicSessionRuntimeController.buildStartCommand()

        assertTrue(command.contains("start_output=\$(rc-service x11-session start 2>&1)"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=start-exit="))
        assertTrue(command.contains("__SAAS_X11_DIAG__=openrc start:"))
        assertTrue(command.contains("__SAAS_X11_DIAG__=service status:"))
    }

    @Test
    fun openRcAndSystemdRemainSeparateWithoutUnprovenStateMutation() {
        val command = GraphicSessionRuntimeController.buildStartCommand()
        val stop = GraphicSessionRuntimeController.buildStopCommand()

        assertFalse(command.contains("rc-service x11-session zap"))
        assertFalse(command.contains("rm -f /etc/runlevels/default/x11-session"))
        assertFalse(command.contains("rm -f /run/x11-session.pid"))
        assertFalse(stop.contains("rc-service x11-session zap"))
        assertTrue(command.contains("systemctl reset-failed x11-session.service"))
    }
}
