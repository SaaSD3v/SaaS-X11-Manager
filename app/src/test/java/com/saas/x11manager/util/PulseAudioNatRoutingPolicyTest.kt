package com.saas.x11manager.util

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioNatRoutingPolicyTest {
    private fun projectFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile || candidate.isDirectory) return candidate
            current = current.parentFile
        }
        error("Could not locate project path: $relativePath")
    }

    @Test
    fun natUsesTrackedListenerWhileHostKeepsValidatedUnifiedFinalizer() {
        val session = projectFile(
            "app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt"
        ).readText()

        assertTrue(session.contains("if (mode == \"nat\")"))
        assertTrue(session.contains("NAT audio finalizer: tracked authenticated listener"))
        assertTrue(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioUnifiedTransport.finalizeAfterContainerReady"))
    }
}
