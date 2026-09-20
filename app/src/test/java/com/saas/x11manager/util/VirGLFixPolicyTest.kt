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
    fun managerOwnsPrivateRendererSocketAndGuestContract() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt"
        )
        val config = source(
            "app/src/main/java/com/saas/x11manager/util/VirGLContainerConfig.kt"
        )

        assertTrue(manager.contains("virgl_test_server_android"))
        assertTrue(manager.contains("--socket-path"))
        assertTrue(manager.contains("GALLIUM_DRIVER=virpipe"))
        assertTrue(manager.contains("VTEST_SOCKET_NAME="))
        assertTrue(manager.contains("UnixSocketTableParser.findInode"))
        assertTrue(manager.contains("processStartTime"))
        assertTrue(config.contains("/.saas-x11-manager/virgl/runtime"))
        assertTrue(config.contains("/usr/.saas-virgl"))
        assertFalse(config.contains("/tmp/.virgl_test"))
    }

    @Test
    fun liveManagerRendererCanRecoverMissingLeaseWithoutBlindAdoption() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt"
        )

        assertTrue(manager.contains("recoverLiveHostLease"))
        assertTrue(manager.contains("pidof virgl_test_server_android"))
        assertTrue(manager.contains("processUid(pid) != 0"))
        assertTrue(manager.contains("--socket-path \$HOST_SOCKET"))
        assertFalse(manager.contains("processOwnsSocket(pid, inode)"))
        assertTrue(manager.contains("socketInode(HOST_SOCKET) == null"))
        assertTrue(manager.contains("candidates.size != 1"))
        assertTrue(manager.contains("su \${runtime.uid} -c"))
        assertTrue(manager.contains("readLease() == lease"))
        assertTrue(manager.contains("processUid(lease.pid) != 0"))
        assertTrue(manager.contains("cmdline.contains(\"--socket-path \$HOST_SOCKET\")"))
        assertTrue(manager.contains("Lease recovery could not persist virgl.pid"))
        assertTrue(manager.contains("chmod 666"))
        assertTrue(manager.contains("Retiring unrecoverable renderers on the Manager-private socket"))
    }

    @Test
    fun stalePrivateRendererWorkersAreRetiredWithoutTouchingNativeVirgl() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt"
        )

        assertTrue(manager.contains("exactPrivateRenderers"))
        assertTrue(manager.contains("cmdline.contains(\"--socket-path \$HOST_SOCKET\")"))
        assertTrue(manager.contains("stopExactPrivateRenderers"))
        assertTrue(manager.contains("Retiring unrecoverable renderers on the Manager-private socket"))
        assertFalse(manager.contains("pkill virgl_test_server_android"))
    }

    @Test
    fun realRendererProbeFallsBackFromGlxinfoToEglinfo() {
        val manager = source(
            "app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt"
        )

        assertTrue(manager.contains("glxinfo -B"))
        assertTrue(manager.contains("eglinfo -B"))
        assertTrue(manager.indexOf("glxinfo -B") < manager.indexOf("eglinfo -B"))
        assertTrue(manager.contains("renderer:.*(virgl|virpipe)"))
        assertTrue(manager.contains("__SAAS_VIRGL_RENDERER_READY__"))
    }

    @Test
    fun droidspacesNativeVirglIsDisabledAndRestorable() {
        val config = source(
            "app/src/main/java/com/saas/x11manager/util/VirGLContainerConfig.kt"
        )
        val settings = source(
            "app/src/main/java/com/saas/x11manager/util/FixSettings.kt"
        )

        assertTrue(config.contains("enable_virgl"))
        assertTrue(config.contains("buildRestoredConfig"))
        assertTrue(settings.contains("getVirGLOriginalState"))
        assertTrue(settings.contains("clearVirGLRuntimeState"))
    }

    @Test
    fun x11StartFinalizesVirglBeforeDesktopHandshake() {
        val access = source(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        )

        assertTrue(access.contains("VirGLFixManager.prepareBeforeGraphicalStart"))
        assertTrue(access.contains("VirGLFixManager.finalizeAfterContainerReady"))
        assertTrue(
            access.indexOf("VirGLFixManager.finalizeAfterContainerReady") <
                access.indexOf("finalizeAudioAfterContainerReady(containerName, logger)", 
                    access.indexOf("VirGLFixManager.finalizeAfterContainerReady"))
        )
    }
}
