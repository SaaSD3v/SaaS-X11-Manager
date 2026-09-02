package com.saas.x11manager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

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

    private fun embeddedHelper(): ByteArray {
        val dir = projectFile("app/src/main/assets/saas-audio")
        val parts = dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.matches(Regex("part\\d{2}\\.b64")) }
            .sortedBy { it.name }
        assertEquals(listOf("part00.b64", "part01.b64", "part02.b64", "part03.b64", "part04.b64"), parts.map { it.name })
        val encoded = parts.joinToString(separator = "") { it.readText().filterNot(Char::isWhitespace) }
        assertEquals(92_700, encoded.length)
        return Base64.getDecoder().decode(encoded)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun fixIsOptInAndExposedBelowGeneralSettings() {
        val settings = source("app/src/main/java/com/saas/x11manager/util/FixSettings.kt")
        val card = source("app/src/main/java/com/saas/x11manager/ui/component/ContainerCard.kt")
        val home = source("app/src/main/java/com/saas/x11manager/ui/screen/HomeScreen.kt")

        assertTrue(settings.contains("getBoolean(PULSEAUDIO_PREFIX + containerName, false)"))
        assertTrue(card.contains("val onFixes: () -> Unit = {}"))
        val generalIndex = card.indexOf("label = \"General settings\"")
        val fixesIndex = card.indexOf("label = \"Fixes\"")
        assertTrue(generalIndex >= 0)
        assertTrue(fixesIndex > generalIndex)
        assertTrue(home.contains("FixesDialog("))
        assertTrue(home.contains("onFixes ="))
    }

    @Test
    fun exactAuditedHelperIsEmbeddedAndFailsClosedOnIntegrityMismatch() {
        val helper = embeddedHelper()
        val manager = source("app/src/main/java/com/saas/x11manager/util/PulseAudioFixManager.kt")

        assertEquals(69_523, helper.size)
        assertEquals(
            "55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278",
            sha256(helper)
        )
        assertTrue(manager.contains("SCRIPT_SHA256 = \"55cd6d74323a76fe44a07ac5c8777ff843ab13427b57cec686a3a9262c919278\""))
        assertTrue(manager.contains("failed its integrity check"))
        assertTrue(manager.contains("availableParts != expectedParts"))
    }

    @Test
    fun helperKeepsNativeFirstSafeFallbackAndDistroDetection() {
        val script = embeddedHelper().toString(Charsets.UTF_8)
        val sessionAccess = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        assertTrue(script.contains("/tmp/.pulse-socket"))
        assertTrue(script.contains("module-aaudio-sink"))
        assertTrue(script.contains("module-sles-sink"))
        assertTrue(script.contains("apt-get install -y pulseaudio-utils libasound2-plugins alsa-utils"))
        assertTrue(script.contains("apk add --no-cache pulseaudio-utils alsa-utils alsa-plugins-pulse"))
        assertTrue(script.contains("LISTEN=\"127.0.0.1\""))
        assertFalse(script.contains("listen=0.0.0.0"))
        assertTrue(script.contains("--restore-state"))
        assertTrue(script.contains("--uninstall"))
        assertTrue(sessionAccess.contains("PulseAudioFixManager.ensureIfEnabled"))
    }

    @Test
    fun embeddedHelperPassesPosixShellSyntaxCheck() {
        val temp = File.createTempFile("saas-audio-helper", ".sh")
        try {
            temp.writeBytes(embeddedHelper())
            val process = ProcessBuilder("sh", "-n", temp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            assertEquals("sh -n failed: $output", 0, exit)
        } finally {
            temp.delete()
        }
    }
}
