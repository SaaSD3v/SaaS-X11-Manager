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
    fun natIdentityIsPreparedBeforeGraphicalStartAndUsesOwnFinalizer() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val base = session.indexOf("PulseAudioFixManager.prepareBeforeGraphicalStart")
        val identity = session.indexOf("PulseAudioNatIdentityTransport.prepareBeforeGraphicalStart")
        val x11 = session.indexOf("X11SessionManager.startX11Session")
        assertTrue(base >= 0)
        assertTrue(identity > base)
        assertTrue(x11 > identity)
        assertTrue(session.contains("PulseAudioNatIdentityTransport.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
    }

    @Test
    fun natIdentityTransportPreservesInetAndFailsFast() {
        val source = source("app/src/main/java/com/saas/x11manager/util/PulseAudioNatIdentityTransport.kt")
        assertTrue(source.contains("INET_GID = 3003"))
        assertTrue(source.contains("/data/system/packages.list"))
        assertTrue(source.contains("--supp-group"))
        assertTrue(source.contains(" -G "))
        assertTrue(source.contains("setpriv --reuid"))
        assertTrue(source.contains("coreHasInet"))
        assertTrue(source.contains("module-native-protocol-tcp"))
        assertTrue(source.contains("auth-cookie=\$COOKIE"))
        assertTrue(source.contains("MAX_PORT_SHIFT = 12"))
        assertTrue(source.contains("NAT audio failed fast"))
        assertFalse(source.contains("auth-anonymous=1"))
        assertFalse(source.contains("listen=0.0.0.0"))
        assertFalse(source.contains("ContainerManager.startContainer("))
        assertFalse(source.contains("ContainerManager.stopContainer("))
        assertFalse(source.contains("systemctl restart"))
        assertFalse(source.contains("rc-service"))
        assertFalse(source.contains("/tmp/.pulse-socket"))
    }
}
