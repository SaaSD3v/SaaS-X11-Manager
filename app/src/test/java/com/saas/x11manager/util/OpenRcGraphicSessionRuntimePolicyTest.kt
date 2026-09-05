package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRcGraphicSessionRuntimePolicyTest {

    @Test
    fun startCommandRecoversStaleOpenRcStateBeforeRetryingSession() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(1))

        assertTrue(command.contains("rm -f /etc/runlevels/default/x11-session"))
        assertTrue(command.contains("rc-service x11-session zap"))
        assertTrue(command.contains("rm -f /run/x11-session.pid"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=crashed-reset"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=service-state-reset"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=start-exit="))
    }

    @Test
    fun startCommandCanConfirmLiveOpenRcPidWithoutStartingDuplicateDesktop() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(3))

        assertTrue(command.contains("cat /run/x11-session.pid"))
        assertTrue(command.contains("kill -0"))
        assertTrue(command.contains("__SAAS_X11_ACTION__=pid-active"))
    }

    @Test
    fun openRcFailureDiagnosticsTravelOnCapturedStdout() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(1))

        assertTrue(command.contains("__SAAS_X11_DIAG__=openrc session did not stabilize"))
        assertTrue(command.contains("__SAAS_X11_DIAG__=service status:"))
        assertTrue(command.contains("__SAAS_X11_DIAG__=pidfile:"))
        assertFalse(command.contains("__SAAS_X11_DIAG__=openrc session did not stabilize' >&2"))
    }

    @Test
    fun systemdStillResetsFailedStateIndependently() {
        val command = GraphicSessionRuntimeController.buildStartCommand(X11DisplaySlot(0))

        assertTrue(command.contains("systemctl reset-failed x11-session.service"))
    }
}
