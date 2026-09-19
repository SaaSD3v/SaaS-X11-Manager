package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxAppContextPolicyTest {
    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        error("Could not locate project path: $relativePath")
    }

    private fun source(path: String): String = projectFile(path).readText()

    @Test
    fun audioAndVirglDoNotUseTheRetiredRunCommandBroker() {
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")
        val virgl = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertFalse(audio.contains("TermuxAppCommand"))
        assertFalse(transport.contains("TermuxAppCommand"))
        assertFalse(virgl.contains("TermuxAppCommand"))
        assertFalse(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertFalse(manifest.contains("com.termux.app.RunCommandService"))
    }

    @Test
    fun audioKeepsValidatedTermuxUidBaselineAndVirglUsesDroidspacesRootIdentity() {
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")
        val virgl = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")
        val dollar = 36.toChar()

        assertTrue(audio.contains("su " + dollar + "{runtime.uid} -c"))
        assertTrue(transport.contains("su " + dollar + "{owner.uid} -c"))
        assertTrue(virgl.contains("u:r:droidspacesd:s0"))
        assertTrue(virgl.contains("= 0 ] || exit 1"))
        assertTrue(virgl.contains("virgl_test_server_android"))
        assertTrue(virgl.contains("--socket-path"))
        assertFalse(virgl.contains("pkg update"))
        assertFalse(virgl.contains("pkg install"))
    }

    @Test
    fun retiredReconstructedAudioHelperIsNotReferenced() {
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertFalse(audio.contains("Base64"))
        assertFalse(audio.contains("SaaS-DroidSpaces-Audio-Auto.sh"))
        assertFalse(audio.contains("context.assets.open"))
        assertFalse(audio.contains("SCRIPT_SHA256"))
        assertTrue(audio.contains("Physically validated transport baseline"))
    }
}
