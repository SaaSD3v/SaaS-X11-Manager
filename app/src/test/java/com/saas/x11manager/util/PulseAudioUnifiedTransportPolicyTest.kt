package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioUnifiedTransportPolicyTest {
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
    fun hostAndNatShareOnePostReadyTransport() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        assertTrue(session.contains("PulseAudioFixManager.prepareBeforeGraphicalStart"))
        assertTrue(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioNatTransport"))
        assertFalse(session.contains("PulseAudioNatRunCommandTransport"))
        assertFalse(session.contains("PulseAudioNatIdentityTransport"))
        assertFalse(session.contains("PulseAudioNatStartupFallback"))
    }

    @Test
    fun manifestDeclaresTermuxPermissionAndVisibility() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertTrue(manifest.contains("<queries>"))
        assertTrue(manifest.contains("<package android:name=\"com.termux\""))
    }

    @Test
    fun unifiedTransportOnlyControlsExistingCoreThroughRealTermux() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")

        assertTrue(transport.contains("RUN_PERMISSION = \"com.termux.permission.RUN_COMMAND\""))
        assertTrue(transport.contains("ComponentName(TERMUX_PACKAGE, RUN_SERVICE)"))
        assertTrue(transport.contains("context.startService(intent)"))
        assertTrue(transport.contains("putExtra(RUN_PATH, TERMUX_SH)"))
        assertTrue(transport.contains("putExtra(RUN_ARGS, arrayOf(\"-c\", command))"))
        assertTrue(transport.contains("allow-external-apps=true"))
        assertTrue(transport.contains("Audio control executor: Termux RunCommandService"))
        assertTrue(transport.contains("module-native-protocol-tcp"))
        assertTrue(transport.contains("auth-cookie=\${'$'}COOKIE"))
        assertTrue(transport.contains("\"host\" -> \"127.0.0.1\""))
        assertTrue(transport.contains("discoverNatGateway"))
        assertTrue(transport.contains("172.28.0.1"))
        assertTrue(transport.contains("BASE_PORT = 4713"))
        assertTrue(transport.contains("MAX_PORT_SHIFT = 64"))

        assertFalse(transport.contains("pulseaudio -n"))
        assertFalse(transport.contains("module-aaudio-sink"))
        assertFalse(transport.contains("module-sles-sink"))
        assertFalse(transport.contains("INET_GID"))
        assertFalse(transport.contains("setpriv"))
        assertFalse(transport.contains("--supp-group"))
        assertFalse(transport.contains("auth-anonymous=1"))
        assertFalse(transport.contains("listen=0.0.0.0"))
        assertFalse(transport.contains("eval "))
        assertFalse(transport.contains("ContainerManager.startContainer("))
        assertFalse(transport.contains("ContainerManager.stopContainer("))
        assertFalse(transport.contains("systemctl restart"))
        assertFalse(transport.contains("rc-service"))
        assertFalse(transport.contains("/tmp/.pulse-socket"))
    }

    @Test
    fun listenerCommandMatchesPhysicalManualProof() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")
        assertTrue(transport.contains("pactl load-module module-native-protocol-tcp"))
        assertTrue(transport.contains("\"listen=\${'$'}IP\""))
        assertTrue(transport.contains("\"port=\${'$'}PORT\""))
        assertTrue(transport.contains("\"auth-cookie=\${'$'}COOKIE\""))
        assertTrue(transport.contains("PULSE_SERVER=\"\${'$'}unix\""))
        assertTrue(transport.contains("PULSE_SERVER=\"\${'$'}tcp\""))
    }
}
