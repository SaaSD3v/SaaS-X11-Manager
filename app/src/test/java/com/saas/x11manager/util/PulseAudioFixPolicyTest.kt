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
    fun fixIsOptInAndUsesAFullScreenSettingsPage() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val fixes = source("app/src/main/java/com/saas/x11manager/ui/screen/FixesDialog.kt")
        val card = source("app/src/main/java/com/saas/x11manager/ui/component/ContainerCard.kt")

        assertTrue(settings.contains("getBoolean(PULSEAUDIO_PREFIX + containerName, false)"))
        assertTrue(fixes.contains("fillMaxSize()"))
        assertFalse(fixes.contains("AlertDialog("))
        assertTrue(fixes.contains("\"PulseAudio fix\""))
        assertTrue(fixes.contains("\"Android audio for Linux applications\""))
        assertFalse(fixes.contains("Magisk"))
        assertFalse(fixes.contains("Root handling"))

        val generalIndex = card.indexOf("label = \"General settings\"")
        val fixesIndex = card.indexOf("label = \"Fixes\"")
        assertTrue(generalIndex >= 0)
        assertTrue(fixesIndex > generalIndex)
    }

    @Test
    fun audioFixNeverOwnsTheContainerLifecycle() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains("prepareBeforeGraphicalStart"))
        assertTrue(manager.contains("finalizeAfterContainerReady"))
        assertFalse(manager.contains("ContainerManager.startContainer("))
        assertFalse(manager.contains("ContainerManager.stopContainer("))
        assertFalse(manager.contains("--restore-state"))
        assertFalse(manager.contains("--uninstall"))
        assertFalse(manager.contains("Magisk"))
        assertFalse(manager.contains("ROOT_RPC"))
    }

    @Test
    fun nativeAudioIsPreparedBeforeNormalStartAndVerifiedAfterIt() {
        val sessionAccess = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        val prepareIndex = sessionAccess.indexOf("PulseAudioFixManager.prepareBeforeGraphicalStart")
        val x11Index = sessionAccess.indexOf("X11SessionManager.startX11Session")
        val finalizeIndex = sessionAccess.indexOf("PulseAudioFixManager.finalizeAfterContainerReady")
        assertTrue(prepareIndex >= 0)
        assertTrue(x11Index > prepareIndex)
        assertTrue(finalizeIndex > x11Index)

        assertTrue(manager.contains("enable_pulseaudio"))
        assertTrue(manager.contains("/tmp/.pulse-socket"))
        assertTrue(manager.contains("AAudio_sink"))
        assertTrue(manager.contains("OpenSL_ES_sink"))
        assertTrue(manager.contains("Graphical startup will continue"))
    }

    @Test
    fun distroClientInstallationSupportsDebianUbuntuAndAlpine() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(manager.contains("apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils"))
        assertTrue(manager.contains("apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse"))
        assertTrue(manager.contains("Installing missing Debian/Ubuntu audio clients"))
        assertTrue(manager.contains("Installing missing Alpine audio clients"))
    }

    @Test
    fun originalContainerPulseSettingIsTrackedForDisable() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertTrue(settings.contains("PULSEAUDIO_ORIGINAL_PREFIX"))
        assertTrue(settings.contains("getPulseAudioOriginalState"))
        assertTrue(settings.contains("clearPulseAudioRuntimeState"))
        assertTrue(manager.contains("restoreContainerConfig"))
        assertTrue(manager.contains("removePulseState"))
    }
}
