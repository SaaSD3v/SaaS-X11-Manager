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
    fun natUsesValidatedListenerHandoffWhileHostKeepsUnifiedFinalizer() {
        val session = projectFile(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        ).readText()

        assertTrue(session.contains("if (mode == \"nat\")"))
        assertTrue(session.contains("NAT audio finalizer: host readiness -> validated listener -> container client"))
        assertTrue(session.contains("PulseAudioNatHostReadiness.prepare"))
        assertTrue(session.contains("PulseAudioNatPreflight.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
    }

    @Test
    fun natHostReadinessOnlyReleasesPositivelyOwnedStaleManagerCore() {
        val readiness = projectFile(
            "app/src/main/java/com/saas/x11manager/util/PulseAudioNatHostReadiness.kt"
        ).readText()

        assertTrue(readiness.contains("private const val NAT_GATEWAY = \"172.28.0.1\""))
        assertTrue(readiness.contains("private const val NAT_PORT = 4713"))
        assertTrue(readiness.contains("/proc/net/tcp"))
        assertTrue(readiness.contains("HOME=\$MANAGER_PULSE_HOME"))
        assertTrue(readiness.contains("uid"))
        assertTrue(readiness.contains("*pulseaudio*"))
        assertTrue(readiness.contains("pid == currentPid"))
        assertTrue(readiness.contains("kill \$stalePid"))
        assertTrue(readiness.contains("Existing listener is not proven stale Manager state; it will not be modified"))
        assertFalse(readiness.contains("kill -9"))
        assertFalse(readiness.contains("0.0.0.0"))
    }

    @Test
    fun natFinalizerMirrorsValidatedListenerEnvironmentAndVerifiesRealContainer() {
        val preflight = projectFile(
            "app/src/main/java/com/saas/x11manager/util/PulseAudioNatPreflight.kt"
        ).readText()

        assertTrue(preflight.contains("private const val NAT_GATEWAY = \"172.28.0.1\""))
        assertTrue(preflight.contains("private const val SERVER = \"tcp:\$NAT_GATEWAY:\$BASE_PORT\""))
        assertTrue(preflight.contains("PULSE_SERVER="))
        assertTrue(preflight.contains("PULSE_COOKIE="))
        assertTrue(preflight.contains("PULSE_CLIENTCONFIG="))
        assertTrue(preflight.contains("load-module module-native-protocol-tcp"))
        assertTrue(preflight.contains("auth-cookie=\$COOKIE"))
        assertTrue(preflight.contains("unload-module \$moduleId"))
        assertTrue(preflight.contains("__SAAS_NAT_AUDIO_READY__"))
        assertTrue(preflight.contains("Server String: \${'$'}SERVER"))
        assertTrue(preflight.contains("Default Sink: (AAudio_sink|OpenSL_ES_sink)"))
        assertTrue(preflight.contains("Constants.DS_BINARY_PATH"))
        assertFalse(preflight.contains("auth-anonymous=1"))
        assertFalse(preflight.contains("listen=0.0.0.0"))
        assertFalse(preflight.contains("list short modules"))
    }
}
