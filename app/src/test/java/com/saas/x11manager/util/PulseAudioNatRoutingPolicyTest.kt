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
    fun natUsesScriptParityTransportWhileHostKeepsValidatedFinalizer() {
        val session = projectFile(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        ).readText()

        assertTrue(session.contains("if (mode == \"nat\")"))
        assertTrue(session.contains("PulseAudioNatScriptTransport.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioNatHostReadiness"))
        assertFalse(session.contains("PulseAudioNatPreflight"))
        assertFalse(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
    }

    @Test
    fun natTransportMirrorsPhysicallyValidatedV32ListenerContract() {
        val transport = projectFile(
            "app/src/main/java/com/saas/x11manager/util/PulseAudioNatScriptTransport.kt"
        ).readText()

        assertTrue(transport.contains("private const val BASE_PORT = 4713"))
        assertTrue(transport.contains("private const val MAX_PORT_SHIFT = 64"))
        assertTrue(transport.contains("private const val VERIFIED_NAT_GATEWAY = \"172.28.0.1\""))
        assertTrue(transport.contains("PULSE_SERVER="))
        assertTrue(transport.contains("PULSE_COOKIE="))
        assertTrue(transport.contains("PULSE_CLIENTCONFIG="))
        assertTrue(transport.contains("load-module module-native-protocol-tcp"))
        assertTrue(transport.contains("auth-cookie=\$COOKIE"))
        assertTrue(transport.contains("for (port in BASE_PORT..(BASE_PORT + MAX_PORT_SHIFT))"))
        assertTrue(transport.contains("unload-module \$moduleId"))
        assertFalse(transport.contains("list short modules"))
        assertFalse(transport.contains("auth-anonymous=1"))
        assertFalse(transport.contains("listen=0.0.0.0"))
        assertFalse(transport.contains("/proc/[0-9]*/fd/*"))
    }

    @Test
    fun natTransportVerifiesTheRealDroidSpacesContainer() {
        val transport = projectFile(
            "app/src/main/java/com/saas/x11manager/util/PulseAudioNatScriptTransport.kt"
        ).readText()

        assertTrue(transport.contains("Constants.DS_BINARY_PATH"))
        assertTrue(transport.contains("run /bin/sh -lc"))
        assertTrue(transport.contains("/root/.config/pulse/saas-audio.cookie"))
        assertTrue(transport.contains("default-server = \$server"))
        assertTrue(transport.contains("__SAAS_AUDIO_TRANSPORT_READY__"))
        assertTrue(transport.contains("Default Sink: (AAudio_sink|OpenSL_ES_sink)"))
        assertTrue(transport.contains("NAT audio transport verified from inside the container"))
    }
}
