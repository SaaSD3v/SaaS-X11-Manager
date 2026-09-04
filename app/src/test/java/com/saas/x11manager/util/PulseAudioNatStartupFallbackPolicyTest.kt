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
    fun natStartupFallbackRunsBeforeNormalAudioFinalizer() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val helper = session.indexOf("PulseAudioNatStartupFallback.ensureBeforeFinalize")
        val finalize = session.indexOf("PulseAudioFixManager.finalizeAfterContainerReady", startIndex = helper)
        assertTrue(helper >= 0)
        assertTrue(finalize > helper)
    }

    @Test
    fun fallbackOnlyRebuildsManagerAudioAndPreloadsAuthenticatedNatListener() {
        val source = source("app/src/main/java/com/saas/x11manager/util/PulseAudioNatStartupFallback.kt")

        assertTrue(source.contains("info.netMode.trim().lowercase() != \"nat\""))
        assertTrue(source.contains("module-native-protocol-unix socket=\$HOST_CONTROL_SOCKET auth-cookie=\$HOST_COOKIE"))
        assertTrue(source.contains("module-native-protocol-tcp listen=\${listener.ip} port=\${listener.port} auth-cookie=\$HOST_COOKIE"))
        assertTrue(source.contains("module-aaudio-sink"))
        assertTrue(source.contains("module-sles-sink"))
        assertTrue(source.contains("BASE_AUDIO_PORT = 4713"))
        assertTrue(source.contains("MAX_PORT_SHIFT = 64"))
        assertTrue(source.contains("configuredPortForwardOwner"))
        assertTrue(source.contains("currentManagerListeners"))
        assertTrue(source.contains("container and X11/VNC stay unchanged"))

        assertFalse(source.contains("auth-anonymous=1"))
        assertFalse(source.contains("listen=0.0.0.0"))
        assertFalse(source.contains("ContainerManager.startContainer("))
        assertFalse(source.contains("ContainerManager.stopContainer("))
        assertFalse(source.contains("systemctl restart"))
        assertFalse(source.contains("rc-service"))
        assertFalse(source.contains("/tmp/.pulse-socket"))
    }
}
