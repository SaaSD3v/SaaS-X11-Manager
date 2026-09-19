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
    fun androidFacingBackendsRunThroughRealTermuxAppContext() {
        val broker = source("app/src/main/java/com/saas/x11manager/util/TermuxAppCommand.kt")
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val transport = source("app/src/main/java/com/saas/x11manager/util/PulseAudioUnifiedTransport.kt")
        val virgl = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")

        assertTrue(broker.contains("com.termux.app.RunCommandService"))
        assertTrue(broker.contains("am startservice --user 0"))
        assertTrue(broker.contains("allow-external-apps=true"))
        assertTrue(broker.contains("POLICY_CACHE_MARKER"))
        assertTrue(broker.contains("TermuxAppSharedProperties"))
        assertTrue(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertTrue(manifest.contains("<package android:name=\"com.termux\""))

        assertTrue(audio.contains("TermuxAppCommand.execBlocking"))
        assertTrue(transport.contains("TermuxAppCommand.execBlocking"))
        assertTrue(virgl.contains("TermuxAppCommand.execBlocking"))

        assertFalse(audio.contains("Shell.cmd(\"su \${runtime.uid} -c"))
        assertFalse(transport.contains("Shell.cmd(\"su \${owner.uid} -c"))
        assertFalse(virgl.contains("\"su \${runtime.uid} -c \""))
    }

    @Test
    fun managerStillOwnsPrivateAudioAndVirglEndpoints() {
        val audio = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")
        val virgl = source("app/src/main/java/com/saas/x11manager/util/VirGLFixManager.kt")

        assertTrue(audio.contains("module-native-protocol-unix socket=\$HOST_CONTROL_SOCKET auth-cookie=\$HOST_COOKIE"))
        assertTrue(audio.contains("module-aaudio-sink"))
        assertTrue(audio.contains("module-sles-sink"))

        assertTrue(virgl.contains("virgl_test_server_android"))
        assertTrue(virgl.contains("--socket-path"))
        assertTrue(virgl.contains("HOST_SOCKET"))
    }
}
