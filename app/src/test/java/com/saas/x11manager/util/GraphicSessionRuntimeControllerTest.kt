package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionRuntimeControllerTest {

    @Test
    fun rawSocketCommandAcquiresOnlySocketLease() {
        val command = GraphicSessionRuntimeController.buildSocketEnsureCommand()

        assertTrue(command.contains(GraphicSessionRuntimePolicy.SOCKET_REQUEST_FILE))
        assertFalse(command.contains(": > \"\$session_request\""))
        assertTrue(command.contains("/tmp/.X11-unix/X0"))
        assertTrue(command.contains("test -S"))
        assertTrue(command.contains("systemctl start setup-x11-socket.service"))
        assertTrue(command.contains("rc-service x11-setup start"))
        assertTrue(command.contains("socket-not-visible"))
        assertFalse(command.contains("systemctl start x11-session.service"))
        assertFalse(command.contains("rc-service x11-session start"))
        assertFalse(command.contains(Constants.DS_BINARY_PATH))
    }

    @Test
    fun startCommandRequiresLeaseSocketAndMigratesLegacyLauncher() {
        val command = GraphicSessionRuntimeController.buildStartCommand()

        assertTrue(command.contains(GraphicSessionRuntimePolicy.SOCKET_REQUEST_FILE))
        assertTrue(command.contains(GraphicSessionRuntimePolicy.SESSION_REQUEST_FILE))
        assertTrue(command.contains("had_session_request"))
        assertTrue(command.contains("/usr/local/bin/x11-session.sh"))
        assertTrue(command.contains("grep -Fq"))
        assertTrue(command.contains("/tmp/.X11-unix/X0"))
        assertTrue(command.contains("test -S"))
        assertTrue(command.contains("systemctl start setup-x11-socket.service"))
        assertTrue(command.contains("systemctl is-active --quiet x11-session.service"))
        assertTrue(command.contains("rc-service x11-setup start"))
        assertTrue(command.contains("rc-service x11-session status"))
        assertTrue(command.contains("socket-not-visible"))
        assertFalse(command.contains(Constants.DS_BINARY_PATH))
    }

    @Test
    fun stopCommandStopsSessionThenSocketBridgeAndReleasesMarkers() {
        val command = GraphicSessionRuntimeController.buildStopCommand()

        assertTrue(command.contains("systemctl stop x11-session.service"))
        assertTrue(command.contains("systemctl stop setup-x11-socket.service"))
        assertTrue(command.contains("rc-service x11-session stop"))
        assertTrue(command.contains("rc-service x11-setup stop"))
        assertTrue(command.contains(GraphicSessionRuntimePolicy.SOCKET_REQUEST_FILE))
        assertTrue(command.contains(GraphicSessionRuntimePolicy.SESSION_REQUEST_FILE))
        assertFalse(command.contains(Constants.DS_BINARY_PATH))
    }
}
