package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicSessionRuntimeControllerTest {

    @Test
    fun startCommandRequiresTheSingleDisplaySocketInsideContainer() {
        val command = GraphicSessionRuntimeController.buildStartCommand()

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
    fun stopCommandStopsOnlyTheManagedGraphicSession() {
        val command = GraphicSessionRuntimeController.buildStopCommand()

        assertTrue(command.contains("systemctl stop x11-session.service"))
        assertTrue(command.contains("rc-service x11-session stop"))
        assertFalse(command.contains("systemctl stop setup-x11-socket.service"))
        assertFalse(command.contains("rc-service x11-setup stop"))
        assertFalse(command.contains(Constants.DS_BINARY_PATH))
    }
}
