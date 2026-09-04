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
        val source = source("app/src/main/java/com/saas/x11manager/util/TermuxRunCommandPreflight.kt")

        assertTrue(source.contains("allow-external-apps=true"))
        assertTrue(source.contains("termux-external-apps.cache-v2"))
        assertTrue(source.contains("TermuxAppSharedProperties"))
        assertTrue(source.contains("cmd\" = 'com.termux'") || source.contains("cmd\" = "))
        assertTrue(source.contains("kill -9"))
        assertFalse(source.contains("am force-stop"))
        assertFalse(source.contains("killall"))
        assertFalse(source.contains("pkill -u"))
    }

    @Test
    fun preflightUsesForegroundRunCommandWithRootAmFallbackAndHandshake() {
        val source = source("app/src/main/java/com/saas/x11manager/util/TermuxRunCommandPreflight.kt")

        assertTrue(source.contains("context.startService(intent)"))
        assertTrue(source.contains("am startservice --user 0"))
        assertTrue(source.contains("OK|BRIDGE|ready"))
        assertTrue(source.contains("bridge-preflight"))
        assertTrue(source.contains("Termux RUN_COMMAND bridge ready before audio core"))
        assertTrue(source.contains("RunCommandService|TermuxPluginUtils|allow-external-apps"))
    }
}
