package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermuxRunCommandPreflightPolicyTest {
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
    fun termuxBridgeIsProvedBeforeAudioCoreAndGraphics() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val preflight = session.indexOf("TermuxRunCommandPreflight.prepareBeforeAudioCore")
        val core = session.indexOf("PulseAudioFixManager.prepareBeforeGraphicalStart")
        val x11 = session.indexOf("X11SessionManager.startX11Session")

        assertTrue(preflight >= 0)
        assertTrue(core > preflight)
        assertTrue(x11 > core)
    }

    @Test
    fun preflightInvalidatesCachedExternalAppsPolicyExactlyOnce() {
        val text = source("app/src/main/java/com/saas/x11manager/util/TermuxRunCommandPreflight.kt")

        assertTrue(text.contains("allow-external-apps=true"))
        assertTrue(text.contains("termux-external-apps.cache-v2"))
        assertTrue(text.contains("TermuxAppSharedProperties"))
        assertTrue(text.contains("/proc/[0-9]*"))
        assertTrue(text.contains("kill -9"))
        assertTrue(text.contains("TERMUX_PACKAGE = \"com.termux\""))
        assertFalse(text.contains("am force-stop"))
        assertFalse(text.contains("killall"))
        assertFalse(text.contains("pkill -u"))
    }

    @Test
    fun preflightUsesForegroundRunCommandWithRootAmFallbackAndHandshake() {
        val text = source("app/src/main/java/com/saas/x11manager/util/TermuxRunCommandPreflight.kt")

        assertTrue(text.contains("context.startService(intent)"))
        assertTrue(text.contains("am startservice --user 0"))
        assertTrue(text.contains("OK|BRIDGE|ready"))
        assertTrue(text.contains("bridge-preflight"))
        assertTrue(text.contains("Termux RUN_COMMAND bridge ready before audio core"))
        assertTrue(text.contains("RunCommandService|TermuxPluginUtils|allow-external-apps"))
    }
}
