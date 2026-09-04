package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PulseAudioNatStartupFallbackPolicyTest {
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
    fun natUsesSingleTransportAndHostKeepsValidatedPath() {
        val session = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        assertTrue(session.contains("PulseAudioNatTransport.prepareBeforeGraphicalStart"))
        assertTrue(session.contains("PulseAudioNatTransport.finalizeAfterContainerReady"))
        assertTrue(session.contains("PulseAudioFixManager.finalizeAfterContainerReady"))
        assertFalse(session.contains("PulseAudioNatRunCommandTransport"))
        assertFalse(session.contains("PulseAudioNatIdentityTransport"))
        assertFalse(session.contains("PulseAudioNatStartupFallback"))
    }

    @Test
    fun manifestDeclaresTermuxRunCommandPermission() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("com.termux.permission.RUN_COMMAND"))
    }

    @Test
    fun natTransportUsesAppContextRunCommandAndTermuxShell() {
        val source = source("app/src/main/java/com/saas/x11manager/util/PulseAudioNatTransport.kt")
        assertTrue(source.contains("RUN_PERMISSION = \"com.termux.permission.RUN_COMMAND\""))
        assertTrue(source.contains("pm grant"))
        assertTrue(source.contains("checkSelfPermission"))
        assertTrue(source.contains("ComponentName(TERMUX_PACKAGE, RUN_SERVICE)"))
        assertTrue(source.contains("context.startForegroundService(intent)"))
        assertTrue(source.contains("putExtra(RUN_PATH, TERMUX_SH)"))
        assertTrue(source.contains("putExtra(RUN_ARGS, arrayOf(script))"))
        assertTrue(source.contains("allow-external-apps=true"))
        assertTrue(source.contains("com.termux.app.reload_style"))
        assertTrue(source.contains("restorecon -RF"))
        assertTrue(source.contains("INET_GID = 3003"))
        assertTrue(source.contains("module-native-protocol-tcp"))
        assertTrue(source.contains("auth-cookie=\${COOKIE}"))
        assertTrue(source.contains("NAT audio failed fast"))

        assertFalse(source.contains("am startservice"))
        assertFalse(source.contains("setpriv --reuid"))
        assertFalse(source.contains("--supp-group"))
        assertFalse(source.contains("auth-anonymous=1"))
        assertFalse(source.contains("listen=0.0.0.0"))
        assertFalse(source.contains("ContainerManager.startContainer("))
        assertFalse(source.contains("ContainerManager.stopContainer("))
        assertFalse(source.contains("systemctl restart"))
        assertFalse(source.contains("rc-service"))
        assertFalse(source.contains("/tmp/.pulse-socket"))
    }
}
