package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioFixPolicyTest {

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
    fun audioConfigurationIsOptInAndUsesAFullScreenSettingsPage() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val fixes = source("app/src/main/java/com/saas/x11manager/ui/screen/FixesDialog.kt")
        val card = source("app/src/main/java/com/saas/x11manager/ui/component/ContainerCard.kt")

        assertTrue(settings.contains("getBoolean(PULSEAUDIO_PREFIX + containerName, false)"))
        assertTrue(fixes.contains("fillMaxSize()"))
        assertFalse(fixes.contains("AlertDialog("))
        assertTrue(fixes.contains("\"Audio configuration\""))
        assertTrue(fixes.contains("\"Android audio for Linux applications\""))
        assertTrue(fixes.contains("\"HOST and NAT network modes supported\""))
        assertFalse(fixes.contains("Magisk"))
        assertFalse(fixes.contains("Root handling"))

        val generalIndex = card.indexOf("label = \"General settings\"")
        val fixesIndex = card.indexOf("label = \"Fixes\"")
        assertTrue(generalIndex >= 0)
        assertTrue(fixesIndex > generalIndex)
    }

    @Test
    fun audioManagerNeverOwnsContainerOrX11Lifecycle() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains("prepareBeforeGraphicalStart"))
        assertTrue(manager.contains("finalizeAfterContainerReady"))
        assertFalse(manager.contains("ContainerManager.startContainer("))
        assertFalse(manager.contains("ContainerManager.stopContainer("))
        assertFalse(manager.contains("systemctl restart"))
        assertFalse(manager.contains("rc-service"))
        assertFalse(manager.contains("X11SessionManager.startX11Session"))
        assertFalse(manager.contains("VncManager"))
    }

    @Test
    fun sessionOrderingKeepsAudioPreparationBeforeGraphicsAndTransportAfterContainerReady() {
        val sessionAccess = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        val prepareIndex = sessionAccess.indexOf("PulseAudioFixManager.prepareBeforeGraphicalStart")
        val x11Index = sessionAccess.indexOf("X11SessionManager.startX11Session")
        val finalizeIndex = sessionAccess.indexOf("PulseAudioFixManager.finalizeAfterContainerReady")
        assertTrue(prepareIndex >= 0)
        assertTrue(x11Index > prepareIndex)
        assertTrue(finalizeIndex > x11Index)
    }

    @Test
    fun hostAndNatUseThePhysicallyValidatedPrivateCoreArchitecture() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains("BASE_AUDIO_PORT = 4713"))
        assertTrue(manager.contains("MAX_PORT_SHIFT = 64"))
        assertTrue(manager.contains("HOST_CONTROL_SOCKET"))
        assertTrue(manager.contains("module-native-protocol-unix socket=\$HOST_CONTROL_SOCKET auth-cookie=\$HOST_COOKIE"))
        assertTrue(manager.contains("module-native-protocol-tcp"))
        assertTrue(manager.contains("auth-cookie=\$HOST_COOKIE"))
        assertTrue(manager.contains("module-aaudio-sink"))
        assertTrue(manager.contains("module-sles-sink"))
        assertTrue(manager.contains("\"host\" -> \"127.0.0.1\""))
        assertTrue(manager.contains("discoverNatGateway"))
        assertTrue(manager.contains("/proc/\${'$'}pid/net/route"))
        assertTrue(manager.contains("172.28.0.1"))
        assertTrue(manager.contains("configuredPortForwardOwner"))
        assertTrue(manager.contains("selectAndEnsureListener"))

        assertFalse(manager.contains("auth-anonymous=1"))
        assertFalse(manager.contains("listen=0.0.0.0"))
        assertFalse(manager.contains("PULSE_SERVER=unix:/tmp/.pulse-socket"))
        assertFalse(manager.contains("waitForContainerBridge"))
        assertFalse(manager.contains("HOST_SOCKET"))
    }

    @Test
    fun binaryCookieNeverTravelsRawThroughDroidSpacesCommandString() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains("transport.cookie"))
        assertTrue(manager.contains("dd if=/dev/urandom"))
        assertTrue(manager.contains("od -An -v -tu1"))
        assertTrue(manager.contains("printf '%b'"))
        assertTrue(manager.contains("COOKIE_ESCAPED"))
        assertTrue(manager.contains("cookie-file = /root/.config/pulse/saas-audio.cookie"))
        assertTrue(manager.contains("PULSE_COOKIE=/root/.config/pulse/saas-audio.cookie"))
    }

    @Test
    fun managerMigratesTheValidatedShellBaselinesAndPreviousHostOnlyApk() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains(".saas-droidspaces-audio-hostnat"))
        assertTrue(manager.contains(".saas-droidspaces-audio-netlab"))
        assertTrue(manager.contains("SaaS DroidSpaces Audio HostNAT"))
        assertTrue(manager.contains("SaaS DroidSpaces Audio NetLab"))
        assertTrue(manager.contains("Migrating previous HOST-only Manager audio runtime"))
        assertTrue(manager.contains("migratedOriginalPulseState"))
    }

    @Test
    fun droidspacesNativeAudioIsDisabledForFutureStartsWithoutRestartingRuntime() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(settings.contains("PULSEAUDIO_ORIGINAL_PREFIX"))
        assertTrue(settings.contains("getPulseAudioOriginalState"))
        assertTrue(settings.contains("clearPulseAudioRuntimeState"))
        assertTrue(manager.contains("setPulseState(info.configPath, enabled = false)"))
        assertTrue(manager.contains("restoreContainerConfig"))
        assertTrue(manager.contains("removePulseState"))
        assertFalse(manager.contains("setPulseState(info.configPath, enabled = true)"))
    }

    @Test
    fun persistentClientConfigurationSupportsDebianUbuntuAndAlpine() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains("default-server = \$server"))
        assertTrue(manager.contains("export PULSE_SERVER=\$server"))
        assertTrue(manager.contains("apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils"))
        assertTrue(manager.contains("apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse"))
        assertTrue(manager.contains("Persistent container audio client already configured"))
        assertTrue(manager.contains("Installing missing Debian/Ubuntu audio clients"))
        assertTrue(manager.contains("Installing missing Alpine audio clients"))
    }
}
