package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioNatStartupFallbackPolicyTest {
    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile || candidate.isDirectory) return candidate
            current = current.parentFile
        }
        error("Could not locate project path: $relativePath")
    }

    private fun source(relativePath: String): String = projectFile(relativePath).readText()

    @Test
    fun natRunCommandIsPreparedBeforeGraphicalStartAndUsesOwnFinalizer() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val base = session.indexOf("PulseAudioFixManager.prepareBeforeGraphicalStart")
        val runCommand = session.indexOf("PulseAudioNatRunCommandTransport.prepareBeforeGraphicalStart")
        val x11 = session.indexOf("X11SessionManager.startX11Session")
        assertTrue(base >= 0)
        assertTrue(runCommand > base)
        assertTrue(x11 > runCommand)
        assertTrue(session.contains("PulseAudioNatRunCommandTransport.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioNatIdentityTransport.prepareBeforeGraphicalStart"))
        assertFalse(session.contains("PulseAudioNatIdentityTransport.finalizeAfterContainerReady"))
    }

    @Test
    fun natRunCommandUsesRealTermuxContextAndAuthenticatedTransport() {
        val source = source("app/src/main/java/com/saas/x11manager/util/PulseAudioNatRunCommandTransport.kt")
        assertTrue(source.contains("com.termux.app.RunCommandService"))
        assertTrue(source.contains("com.termux.RUN_COMMAND"))
        assertTrue(source.contains("com.termux.RUN_COMMAND_PATH"))
        assertTrue(source.contains("com.termux.RUN_COMMAND_BACKGROUND"))
        assertTrue(source.contains("allow-external-apps=true"))
        assertTrue(source.contains("com.termux.app.reload_style"))
        assertTrue(source.contains("INET_GID = 3003"))
        assertTrue(source.contains("executor=termux-run-command"))
        assertTrue(source.contains("module-native-protocol-tcp"))
        assertTrue(source.contains("auth-cookie=\${COOKIE}"))
        assertTrue(source.contains("MAX_PORT_SHIFT = 12"))
        assertTrue(source.contains("NAT audio failed fast"))
        assertFalse(source.contains("auth-anonymous=1"))
        assertFalse(source.contains("listen=0.0.0.0"))
        assertFalse(source.contains("setpriv --reuid"))
        assertFalse(source.contains("--supp-group"))
        assertFalse(source.contains("ContainerManager.startContainer("))
        assertFalse(source.contains("ContainerManager.stopContainer("))
        assertFalse(source.contains("systemctl restart"))
        assertFalse(source.contains("rc-service"))
        assertFalse(source.contains("/tmp/.pulse-socket"))
    }
}
