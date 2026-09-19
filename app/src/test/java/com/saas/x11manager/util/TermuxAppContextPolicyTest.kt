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
    fun virglUsesTermuxAppContextButAudioKeepsValidatedUidBaseline() {
        val broker = source("app/src/main/java/com/saas/x11manager/util/TermuxAppCommand.kt")
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")
        val virgl = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(broker.contains("com.termux.app.RunCommandService"))
        assertTrue(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertTrue(manifest.contains("<package android:name=\"com.termux\""))
        assertTrue(virgl.contains("TermuxAppCommand.execBlocking"))

        // Audio is intentionally pinned to the physically validated 0f61eaa3
        // baseline and must not be silently routed through the later broker.
        assertFalse(audio.contains("TermuxAppCommand.execBlocking"))
        assertFalse(transport.contains("TermuxAppCommand.execBlocking"))
        assertTrue(audio.contains("su ${runtime.uid} -c"))
        assertTrue(transport.contains("su ${owner.uid} -c"))
    }

    @Test
    fun retiredReconstructedAudioHelperIsNotReferenced() {
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertFalse(audio.contains("saas-audio"))
        assertFalse(audio.contains("Base64"))
        assertFalse(audio.contains("SaaS-DroidSpaces-Audio-Auto.sh"))
        assertTrue(audio.contains("Physically validated transport baseline"))
    }
}
