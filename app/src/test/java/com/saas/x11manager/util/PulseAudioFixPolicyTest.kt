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
        assertTrue(fixes.contains("\"HOST network mode currently supported\""))
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
        assertFalse(manager.contains("--restore-state"))
        assertFalse(manager.contains("--uninstall"))
    }

    @Test
    fun managerOwnedHostAudioReplacesTheRetiredNativeSocketTransport() {
        val sessionAccess = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        val prepareIndex = sessionAccess.indexOf("PulseAudioFixManager.prepareBeforeGraphicalStart")
        val x11Index = sessionAccess.indexOf("X11SessionManager.startX11Session")
        val finalizeIndex = sessionAccess.indexOf("PulseAudioFixManager.finalizeAfterContainerReady")
        assertTrue(prepareIndex >= 0)
        assertTrue(x11Index > prepareIndex)
        assertTrue(finalizeIndex > x11Index)

        assertTrue(manager.contains("AUDIO_PORT = 4713"))
        assertTrue(manager.contains("HOST_SERVER = \"tcp:\$AUDIO_HOST:\$AUDIO_PORT\""))
        assertTrue(manager.contains("module-native-protocol-tcp listen=\$AUDIO_HOST port=\$AUDIO_PORT auth-anonymous=1"))
        assertTrue(manager.contains("module-aaudio-sink"))
        assertTrue(manager.contains("module-sles-sink"))
        assertTrue(manager.contains("info.netMode.trim().lowercase() == \"host\""))
        assertTrue(manager.contains("net_mode=host only"))

        assertFalse(manager.contains("PULSE_SERVER=unix:/tmp/.pulse-socket"))
        assertFalse(manager.contains("waitForContainerBridge"))
        assertFalse(manager.contains("HOST_SOCKET"))
        assertFalse(manager.contains("default.pa"))
    }

    @Test
    fun hostRuntimeUsesAnIsolatedPulseAudioEnvironmentAndSurvivesRebootRecovery() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains(".saas-x11-manager/audio"))
        assertTrue(manager.contains("PULSE_CONFIG_PATH"))
        assertTrue(manager.contains("PULSE_RUNTIME_PATH"))
        assertTrue(manager.contains("PULSE_STATE_PATH"))
        assertTrue(manager.contains("PULSE_CLIENTCONFIG"))
        assertTrue(manager.contains("pulseaudio -n --daemonize=no --exit-idle-time=-1 --use-pid-file=false"))
        assertTrue(manager.contains("Manager audio runtime already ready"))
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

        assertTrue(manager.contains("default-server = \$HOST_SERVER"))
        assertTrue(manager.contains("export PULSE_SERVER=\$HOST_SERVER"))
        assertTrue(manager.contains("apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils"))
        assertTrue(manager.contains("apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse"))
        assertTrue(manager.contains("Persistent container audio client already configured"))
        assertTrue(manager.contains("Installing missing Debian/Ubuntu audio clients"))
        assertTrue(manager.contains("Installing missing Alpine audio clients"))
    }
}
