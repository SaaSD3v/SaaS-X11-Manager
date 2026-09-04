package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioDataPathTransportPolicyTest {
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
    fun hostAndNatUseSingleCoreWithRootAmTransport() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        assertTrue(session.contains("PulseAudioFixManager.prepareBeforeGraphicalStart"))
        assertTrue(session.contains("PulseAudioRootAmTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioDataPathTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioPhysicalTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
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
    fun hostAndNatControlExistingCoreThroughRootAmRunCommand() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioRootAmTransport.kt")

        assertTrue(transport.contains("RUN_PERMISSION = \"com.termux.permission.RUN_COMMAND\""))
        assertTrue(transport.contains("RUN_SERVICE = \"com.termux.app.RunCommandService\""))
        assertTrue(transport.contains("am startservice --user 0"))
        assertTrue(transport.contains("RUN_PATH"))
        assertTrue(transport.contains("RUN_WORKDIR"))
        assertTrue(transport.contains("RUN_BACKGROUND"))
        assertTrue(transport.contains("allow-external-apps=true"))
        assertTrue(transport.contains("Audio control executor: root am startservice -> Termux RunCommandService"))
        assertTrue(transport.contains("RUN_COMMAND result authority: Termux file handshake"))
        assertTrue(transport.contains("Listener verifier: DroidSpaces container data path"))
        assertTrue(transport.contains("module-native-protocol-tcp"))
        assertTrue(transport.contains("auth-cookie="))
        assertTrue(transport.contains("discoverNatGateway"))
        assertTrue(transport.contains("172.28.0.1"))
        assertTrue(transport.contains("\"host\" -> \"127.0.0.1\""))
        assertTrue(transport.contains("BASE_PORT = 4713"))
        assertTrue(transport.contains("MAX_PORT_SHIFT = 64"))

        assertFalse(transport.contains("context.startService("))
        assertFalse(transport.contains("startForegroundService("))
        assertFalse(transport.contains("ComponentName("))
        assertFalse(transport.contains("Intent("))
        assertFalse(transport.contains("PulseAudioFixManager.finalizeAfterContainerReady"))

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
    fun rootAmDispatcherTreatsHandshakeAsAuthoritative() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioRootAmTransport.kt")
        assertTrue(transport.contains("val launcher ="))
        assertTrue(transport.contains("val resultFile ="))
        assertTrue(transport.contains("launcherBody"))
        assertTrue(transport.contains("chown"))
        assertTrue(transport.contains("chmod 700"))
        assertTrue(transport.contains("exec >"))
        assertTrue(transport.contains("val dispatch ="))
        assertTrue(transport.contains("RUN_COMMAND timed out waiting for Termux result"))
        assertTrue(transport.contains("Do not return early on dispatch.isSuccess == false"))
        assertFalse(transport.contains("if (dispatch?.isSuccess != true)"))
        assertFalse(transport.contains("RUN_COMMAND root am startservice failed"))
    }

    @Test
    fun listenersMatchTheManualProofAndAreVerifiedByContainer() {
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioRootAmTransport.kt")
        assertTrue(transport.contains("pactl load-module module-native-protocol-tcp"))
        assertTrue(transport.contains("pactl list short modules"))
        assertTrue(transport.contains("verifyContainerClientDetailed"))
        assertTrue(transport.contains("Container could not reach"))
        assertTrue(transport.contains("pactl unload-module"))
        assertTrue(transport.contains("PulseAudio listener module loaded: id="))

        // Termux controls the daemon only through the private UNIX socket. The
        // actual TCP verifier is the running container, not a Termux self-probe.
        assertFalse(transport.contains("PULSE_SERVER=\"${'$'}tcp\""))
        assertFalse(transport.contains("while [ \"${'$'}j\" -lt 20 ]"))
    }
}
