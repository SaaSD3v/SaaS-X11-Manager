package com.saas.x11manager.util

import org.junit.Assert.assertTrue
import org.junit.Test

class VncRuntimeSafetyTest {
    @Test
    fun `recorded lease contains pid and proc start time`() {
        val script = VncRuntimeSafety.recordLease("/run/test-vnc", "server", "server_pid")
        assertTrue(script.contains("/proc/\$server_pid/stat"))
        assertTrue(script.contains("server.pid"))
        assertTrue(script.contains("server.start"))
    }

    @Test
    fun `stop validates process identity before signaling`() {
        val script = VncRuntimeSafety.stopOwnedRuntime("/run/test-vnc")
        assertTrue(script.contains("/proc/${'$'}pid/stat"))
        assertTrue(script.contains("${'$'}actual"))
        assertTrue(script.contains("${'$'}expected"))
        assertTrue(script.contains("Xtigervnc"))
        assertTrue(script.contains("x0vncserver"))
        assertTrue(script.contains("unsafe=1"))
        assertTrue(script.indexOf("if [ \"${'$'}unsafe\" -eq 0 ]") < script.indexOf("rm -rf \"/run/test-vnc\""))
    }

    @Test
    fun `port probe accepts only TCP LISTEN state`() {
        val script = VncRuntimeSafety.listeningPort(5901)
        assertTrue(script.contains("[ \"${'$'}state\" = 0A ]"))
        assertTrue(script.contains("/proc/net/tcp"))
        assertTrue(script.contains("/proc/net/tcp6"))
    }

    @Test
    fun `integrated service stop verifies both systemd units`() {
        val script = VncRuntimeSafety.stopIntegratedGraphicService()
        assertTrue(script.contains("x11-session.service"))
        assertTrue(script.contains("setup-x11-socket.service"))
        assertTrue(script.contains("is-active --quiet x11-session.service"))
        assertTrue(script.contains("is-active --quiet setup-x11-socket.service"))
    }
}
