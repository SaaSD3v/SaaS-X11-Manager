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
    fun audioConfigurationRemainsOptInAndKeepsExistingSettingsSurface() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val fixes = source("app/src/main/java/com/saas/x11manager/ui/screen/FixesDialog.kt")

        assertTrue(settings.contains("getBoolean(PULSEAUDIO_PREFIX + containerName, false)"))
        assertTrue(fixes.contains("\"Audio configuration\""))
        assertTrue(fixes.contains("\"Android audio for Linux applications\""))
        assertTrue(fixes.contains("\"HOST and NAT network modes supported\""))
        assertFalse(fixes.contains("Magisk"))
    }

    @Test
    fun activeAudioPathHasExactlyOneEmbeddedProvider() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")
        val service = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntimeService.kt")

        assertTrue(manager.contains("NativeAudioRuntime.ensureCore"))
        assertTrue(manager.contains("NativeAudioRuntime.configureContainer"))
        assertTrue(session.contains("PulseAudioFixManager.prepareBeforeGraphicalStart"))
        assertTrue(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))

        // Retired implementations can remain in source as regression fixtures,
        // but the graphical start path must never dispatch to them.
        assertFalse(session.contains("PulseAudioRuntimeSanitizer.prepare"))
        assertFalse(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioNatScriptTransport.finalizeAfterContainerReady"))
        assertFalse(manager.contains("detectTermuxRuntime"))
        assertFalse(manager.contains("ensureTermuxPackages"))
        assertFalse(manager.contains("runAsTermux"))
        assertFalse(manager.contains("pkg install"))
        assertFalse(manager.contains("/data/data/com.termux"))
        assertFalse(runtime.contains("/data/data/com.termux"))
        assertFalse(service.contains("/data/data/com.termux"))
    }

    @Test
    fun nativeRuntimeUsesAndroidOwnedPathsAndPackagedElfFiles() {
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")
        val service = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntimeService.kt")
        val build = source("app/build.gradle.kts")
        val packager = source("tools/native-audio/prepare_runtime.py")

        assertTrue(runtime.contains("context.noBackupFilesDir"))
        assertTrue(runtime.contains("context.applicationInfo.nativeLibraryDir"))
        assertTrue(runtime.contains("libsaas_pulseaudio_exec.so"))
        assertTrue(runtime.contains("libsaas_pactl_exec.so"))
        assertTrue(runtime.contains("libsaas_pacat_exec.so"))
        assertTrue(service.contains("--dl-search-path="))
        assertTrue(service.contains("module-native-protocol-unix"))
        assertTrue(build.contains("useLegacyPackaging = true"))
        assertTrue(packager.contains("ROOT_PACKAGE = \"pulseaudio\""))
        assertTrue(packager.contains("SHA256"))
    }

    @Test
    fun nativeCoreKeepsAaudioAndSlesWithoutOemHardcodes() {
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")
        val service = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntimeService.kt")
        val combined = runtime + service

        assertTrue(combined.contains("module-aaudio-sink"))
        assertTrue(combined.contains("module-sles-sink"))
        assertTrue(combined.contains("AAudio_sink"))
        assertTrue(combined.contains("OpenSL_ES_sink"))
        assertTrue(combined.contains("transport.cookie"))
        assertTrue(combined.contains("SecureRandom"))
        assertFalse(combined.contains("auth-anonymous=1"))
        assertFalse(combined.contains("listen=0.0.0.0"))
        assertFalse(combined.contains("libskcodec"))
        assertFalse(combined.contains("ro.product.manufacturer"))
        assertFalse(combined.contains("samsung", ignoreCase = true))
    }

    @Test
    fun nativeNatUsesLiveRouteAndNoFixedGatewayFallback() {
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")

        assertTrue(runtime.contains("discoverNatGateway"))
        assertTrue(runtime.contains("nsenter"))
        assertTrue(runtime.contains("/proc/") && runtime.contains("/net/route"))
        assertTrue(runtime.contains("refusing a hardcoded fallback"))
        assertFalse(runtime.contains("172.28.0.1"))
        assertTrue(runtime.contains("BASE_PORT = 4713"))
        assertTrue(runtime.contains("MAX_PORT_SHIFT = 64"))
        assertTrue(runtime.contains("configuredPortForwardOwner"))
        assertTrue(runtime.contains("module-native-protocol-tcp"))
        assertTrue(runtime.contains("auth-cookie="))
    }

    @Test
    fun consumerLifecycleStopsCoreWhenNoContainerUsesAudio() {
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")
        val service = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntimeService.kt")

        assertTrue(runtime.contains("NativeAudioConsumerStore.put"))
        assertTrue(runtime.contains("NativeAudioConsumerStore.remove"))
        assertTrue(runtime.contains("No active audio consumers; stopping embedded core"))
        assertTrue(runtime.contains("ACTION_STOP_IF_IDLE"))
        assertTrue(service.contains("pruneConsumers"))
        assertTrue(service.contains("IDLE_CHECK_LIMIT"))
        assertTrue(service.contains("stopCore()"))
        assertTrue(service.contains("START_NOT_STICKY"))
    }

    @Test
    fun audioCodeStillNeverOwnsContainerOrX11Lifecycle() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")

        for (text in listOf(manager, runtime)) {
            assertFalse(text.contains("ContainerManager.startContainer("))
            assertFalse(text.contains("ContainerManager.stopContainer("))
            assertFalse(text.contains("systemctl restart"))
            assertFalse(text.contains("rc-service"))
            assertFalse(text.contains("X11SessionManager.startX11Session"))
            assertFalse(text.contains("VncManager"))
        }
    }

    @Test
    fun droidspacesNativeAudioIsDisabledAndRestorableWithoutRuntimeRestart() {
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
    fun validatedContainerTransportAndBoundedDroidspacesCommandAreReused() {
        val runtime = source("app/src/main/java/com/saas/x11manager/audio/NativeAudioRuntime.kt")
        val command = source("app/src/main/java/com/saas/x11manager/util/PulseAudioContainerCommand.kt")
        val payload = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")

        assertTrue(runtime.contains("PulseAudioUnifiedTransport.buildContainerPayload"))
        assertTrue(runtime.contains("PulseAudioContainerCommand.build"))
        assertTrue(command.contains("CHUNK_CHARS = 1024"))
        assertTrue(command.contains("Audio command exceeds DroidSpaces argument capacity"))
        assertTrue(payload.contains("pulseaudio-utils libasound2-plugins alsa-utils"))
        assertTrue(payload.contains("apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse"))
        assertTrue(payload.contains("PulseAudioClientConfig.install(server)"))
        assertTrue(payload.contains("echo __READY__"))
    }
}
