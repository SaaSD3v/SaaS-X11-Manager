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
    fun sessionUsesOnePreparedCoreAndOneUnifiedFinalizer() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        assertTrue(session.contains("PulseAudioFixManager.prepareBeforeGraphicalStart"))
        assertTrue(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("TermuxRunCommandPreflight"))
        assertFalse(session.contains("PulseAudioRootAmTransport"))
        assertFalse(session.contains("PulseAudioDataPathTransport"))
        assertFalse(session.contains("PulseAudioNatTransport"))
        assertFalse(session.contains("PulseAudioNatRunCommandTransport"))
        assertFalse(session.contains("PulseAudioNatIdentityTransport"))
        assertFalse(session.contains("PulseAudioNatStartupFallback"))
    }

    @Test
    fun manifestHasNoRunCommandPermissionOrPackageVisibility() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertFalse(manifest.contains("com.termux.app.RunCommandService"))
        assertFalse(manifest.contains("<package android:name=\"com.termux\""))
    }

    @Test
    fun unifiedTransportControlsOnlyTheExistingCoreThroughUnix() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")

        assertTrue(transport.contains("private UNIX socket via Termux UID"))
        assertTrue(transport.contains("TCP self-probe from Manager: disabled"))
        assertTrue(transport.contains("module-native-protocol-tcp"))
        assertTrue(transport.contains("auth-cookie="))
        assertTrue(transport.contains("private fun unixPactl"))
        assertTrue(transport.contains("unix:${'$'}CONTROL"))
        assertTrue(transport.contains("unixPactl(owner, \"list short modules\")"))
        assertTrue(transport.contains("PulseAudio listener module loaded: id="))
        assertTrue(transport.contains("Listener verifier: PulseAudio module table + DroidSpaces container data path"))

        // Check executable integration identifiers, not prose comments.
        assertFalse(transport.contains("com.termux.app.RunCommandService"))
        assertFalse(transport.contains("com.termux.permission.RUN_COMMAND"))
        assertFalse(transport.contains("am startservice"))
        assertFalse(transport.contains("context.startService("))
        assertFalse(transport.contains("startForegroundService("))
        assertFalse(transport.contains("pulseaudio -n"))
        assertFalse(transport.contains("module-aaudio-sink"))
        assertFalse(transport.contains("module-sles-sink"))
        assertFalse(transport.contains("auth-anonymous=1"))
        assertFalse(transport.contains("listen=0.0.0.0"))
        assertFalse(transport.contains("ContainerManager.startContainer("))
        assertFalse(transport.contains("ContainerManager.stopContainer("))
        assertFalse(transport.contains("systemctl restart"))
        assertFalse(transport.contains("rc-service"))
        assertFalse(transport.contains("/tmp/.pulse-socket"))
    }

    @Test
    fun listenerSuccessIsServerModuleProofNotHostTcpProbe() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")

        assertTrue(transport.contains("[PA-LOAD] endpoint="))
        assertTrue(transport.contains("[PA-LOAD] exit="))
        assertTrue(transport.contains("[PA-LOAD] stdout="))
        assertTrue(transport.contains("[PA-LOAD] stderr="))
        assertTrue(transport.contains("findListenerModule"))
        assertTrue(transport.contains("requiredId = id"))
        assertTrue(transport.contains("[PA-MODULE]"))
        assertTrue(transport.contains("pulseaudio.log"))
        assertTrue(transport.contains("verifyContainerClientDetailed"))

        // The Manager never connects back to tcp:<endpoint>:<port>. The running
        // container is the authoritative TCP data-path verifier.
        assertFalse(transport.contains("probeEndpointAndroidSink"))
        assertFalse(transport.contains("endpoint_ready_from_host"))
    }

    @Test
    fun hostNatAndPortForwardRulesMatchPhysicalBaseline() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")

        assertTrue(transport.contains("BASE_PORT = 4713"))
        assertTrue(transport.contains("MAX_PORT_SHIFT = 64"))
        assertTrue(transport.contains("\"host\" -> \"127.0.0.1\""))
        assertTrue(transport.contains("discoverNatGateway"))
        assertTrue(transport.contains("172.28.0.1"))
        assertTrue(transport.contains("configuredPortForwardOwner"))
        assertTrue(transport.contains("port is reserved by DroidSpaces TCP port-forward"))
    }

    @Test
    fun containerIsTheFinalVerifierAndCookieNeverTravelsRaw() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")

        assertTrue(transport.contains("verifyContainerClientDetailed"))
        assertTrue(transport.contains("PULSE_SERVER="))
        assertTrue(transport.contains("PULSE_COOKIE="))
        assertTrue(transport.contains("pactl info"))
        assertTrue(transport.contains("od -An -v -tu1"))
        assertTrue(transport.contains("printf '%b'"))
        assertTrue(transport.contains("COOKIE_ESCAPED"))
        assertTrue(transport.contains("cookie-file = /root/.config/pulse/saas-audio.cookie"))
    }
}
