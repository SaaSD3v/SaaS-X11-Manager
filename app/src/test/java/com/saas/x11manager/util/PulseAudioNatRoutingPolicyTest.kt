package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioNatRoutingPolicyTest {
    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile || candidate.isDirectory) return candidate
            current = current.parentFile
        }
        error("Could not locate project path: $relativePath")
    }

    @Test
    fun natUsesTrackedListenerWhileHostKeepsValidatedUnifiedFinalizer() {
        val session = projectFile(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        ).readText()

        assertTrue(session.contains("if (mode == \"nat\")"))
        assertTrue(session.contains("NAT audio finalizer: tracked authenticated listener"))
        assertTrue(session.contains("PulseAudioNatPreflight.ensureBaseListener"))
        assertTrue(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
    }

    @Test
    fun natPreflightMirrorsValidatedTermuxListenerEnvironmentAndCleansByReturnedId() {
        val preflight = projectFile(
            "app/src/main/java/com/saas/x11manager/util/PulseAudioNatPreflight.kt"
        ).readText()

        assertTrue(preflight.contains("private const val NAT_GATEWAY = \"172.28.0.1\""))
        assertTrue(preflight.contains("PULSE_SERVER="))
        assertTrue(preflight.contains("PULSE_COOKIE="))
        assertTrue(preflight.contains("PULSE_CLIENTCONFIG="))
        assertTrue(preflight.contains("load-module module-native-protocol-tcp"))
        assertTrue(preflight.contains("auth-cookie=\$COOKIE"))
        assertTrue(preflight.contains("unload-module \$moduleId"))
        assertFalse(preflight.contains("auth-anonymous=1"))
        assertFalse(preflight.contains("listen=0.0.0.0"))
        assertFalse(preflight.contains("list short modules"))
    }
}
