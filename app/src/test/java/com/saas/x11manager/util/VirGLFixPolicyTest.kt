package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VirGLFixPolicyTest {

    private fun source(relativePath: String): String {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile
        }
        error("Could not locate source file: $relativePath")
    }

    @Test
    fun managerOwnsPrivateMultiClientRendererSocketAndGuestContract() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")
        val config = source("app/src/main/java/com/saas/x11manager/util/VirGLContainerConfig.kt")

        assertTrue(manager.contains("virgl_test_server_android"))
        assertTrue(manager.contains("--socket-path"))
        assertTrue(manager.contains("--multi-clients"))
        assertTrue(manager.contains("GALLIUM_DRIVER=virpipe"))
        assertTrue(manager.contains("VTEST_SOCKET_NAME="))
        assertTrue(manager.contains("UnixSocketTableParser.findInode"))
        assertTrue(manager.contains("processStartTime"))
        assertTrue(config.contains("/.saas-x11-manager/virgl/runtime"))
        assertTrue(config.contains("/usr/.saas-virgl"))
        assertFalse(config.contains("/tmp/.virgl_test"))
    }

    @Test
    fun liveManagerRendererRecoveryRequiresExactSharedIdentity() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")

        assertTrue(manager.contains("recoverLiveHostLease"))
        assertTrue(manager.contains("pidof virgl_test_server_android"))
        assertTrue(manager.contains("processUid(pid) != 0"))
        assertTrue(manager.contains("cmdline.contains(\"--multi-clients\")"))
        assertTrue(manager.contains("cmdline.contains(\"--socket-path \$HOST_SOCKET\")"))
        assertTrue(manager.contains("stopExactPrivateRenderers"))
        assertTrue(manager.contains("Retiring unrecoverable renderers on the Manager-private socket"))
        assertFalse(manager.contains("pkill virgl_test_server_android"))
    }

    @Test
    fun rendererProbeUsesDynamicX11AppDisplayInsteadOfFixedX0() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")

        assertTrue(manager.contains("displaySlotFromBindMounts(info.bindMounts)"))
        assertTrue(manager.contains("probeGuestRenderer(containerName, displayName)"))
        assertTrue(manager.contains("DISPLAY=\${q(displayName)}"))
        assertFalse(manager.contains("DISPLAY=:0"))
        assertTrue(manager.contains("glxinfo -B"))
        assertTrue(manager.contains("eglinfo -B"))
        assertTrue(manager.indexOf("glxinfo -B") < manager.indexOf("eglinfo -B"))
        assertTrue(manager.contains("renderer:.*(virgl|virpipe)"))
        assertTrue(manager.contains("__SAAS_VIRGL_RENDERER_READY__"))
    }

    @Test
    fun disabledFixCleansResidualStateAfterManualConfigRestore() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")

        assertTrue(manager.contains("hasSavedOriginal"))
        assertTrue(manager.contains("alreadyRestored"))
        assertTrue(manager.contains("cleanupGuestLive(containerName)"))
        assertTrue(manager.contains("clearVirGLRuntimeState"))
    }

    @Test
    fun droidspacesNativeVirglIsDisabledAndRestorable() {
        val config = source("app/src/main/java/com/saas/x11manager/util/VirGLContainerConfig.kt")
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")

        assertTrue(config.contains("enable_virgl"))
        assertTrue(config.contains("buildRestoredConfig"))
        assertTrue(settings.contains("getVirGLOriginalState"))
        assertTrue(settings.contains("clearVirGLRuntimeState"))
    }

    @Test
    fun x11AppStartIntegratesVirglWithoutReplacingAudioOrVnc() {
        val access = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        assertTrue(access.contains("virgl.prepare-host"))
        assertTrue(access.contains("VirGLFixManager.prepareBeforeGraphicalStart"))
        assertTrue(access.contains("virgl.finalize-guest"))
        assertTrue(access.contains("VirGLFixManager.finalizeAfterContainerReady"))
        assertTrue(access.contains("audio.prepare-host"))
        assertTrue(access.contains("audio.finalize-client"))
        assertTrue(access.contains("VncServerManager.startStandalone"))
        assertTrue(
            access.indexOf("VirGLFixManager.finalizeAfterContainerReady") <
                access.indexOf(
                    "finalizeAudioAfterContainerReady(containerName, logger)",
                    access.indexOf("VirGLFixManager.finalizeAfterContainerReady")
                )
        )
    }
    @Test
    fun optionalRendererFlagsAreCapabilityGatedAndHiddenWithTheFix() {
        val manager = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val fixes = source("app/src/main/java/com/saas/x11manager/ui/screen/FixesDialog.kt")
        val flags = source("app/src/main/java/com/saas/x11manager/util/VirGLRuntimeFlags.kt")

        assertTrue(manager.contains("supportedOptionalFlags"))
        assertTrue(manager.contains("--help 2>&1 || true"))
        assertTrue(manager.contains("VirGLRuntimeFlags::fromHelp"))
        assertTrue(manager.contains("VirGLRuntimeFlags.arguments"))
        assertTrue(manager.contains("Renderer flags:"))
        assertTrue(manager.contains("Renderer flag change is pending"))
        assertTrue(settings.contains("VIRGL_FLAGS_KEY"))
        assertTrue(settings.contains("getVirGLRuntimeFlags"))
        assertTrue(fixes.contains("if (virglEnabled)"))
        assertTrue(fixes.contains("VirGLFixManager.supportedOptionalFlags()"))
        assertTrue(flags.contains("--use-egl-surfaceless"))
        assertTrue(flags.contains("--use-gles"))
        assertTrue(flags.contains("--use-glx"))
        assertTrue(flags.contains("--no-fork"))
        assertTrue(flags.contains("--no-loop-or-fork"))
        assertFalse(flags.contains("--socket-path"))
        assertFalse(flags.contains("--multi-clients"))
    }

}
