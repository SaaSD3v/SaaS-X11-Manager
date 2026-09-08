package com.saas.x11manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VncAdbForwardGuidePolicyTest {
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
    fun connectionGuideKeepsPcLocalPortSeparateFromVncServerPort() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("PC local port: \$effectiveLocalPort"))
        assertTrue(guide.contains("adb forward tcp:\$effectiveLocalPort tcp:\$port"))
        assertTrue(guide.contains("127.0.0.1:\$effectiveLocalPort"))
        assertTrue(guide.contains("The Manager does not create the PC-side ADB mapping automatically"))
        assertFalse(guide.contains("adb forward tcp:5901 tcp:5901"))
    }

    @Test
    fun connectionGuideUsesTopicBlocksAndPutsCommandsOnTheirOwnLines() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("[VNC] Connection"))
        assertTrue(guide.contains("[VNC] USB / ADB"))
        assertTrue(guide.contains("[VNC] Authentication"))
        assertTrue(guide.contains("[VNC] Advanced settings"))
        assertTrue(guide.contains("logger.i(LogLayout.SPACER)"))
        assertTrue(guide.contains("logger.i(\"[VNC] • Run on the PC:\")"))
        assertTrue(guide.contains("logger.i(\"[VNC]   adb forward tcp:\$effectiveLocalPort tcp:\$port\")"))
        assertTrue(guide.contains("logger.i(\"[VNC] • When DroidSpaces publishes VNC on Android as <hostPort>, run:\")"))
        assertTrue(guide.contains("logger.i(\"[VNC]   adb forward tcp:\$effectiveLocalPort tcp:<hostPort>\")"))
    }

    @Test
    fun connectionGuideOnlyAdvertisesExactUsbTargetAfterHostPortProbe() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("androidHostPortListening = isAndroidHostPortListening(port)"))
        assertTrue(guide.contains("if (runtime.androidHostPortListening)"))
        assertTrue(guide.contains("Android-side VNC TCP \$port is not confirmed"))
        assertTrue(guide.contains("tcp:<hostPort>"))
        assertTrue(guide.contains("/proc/net/tcp /proc/net/tcp6"))
    }

    @Test
    fun containerAndAndroidHostAddressesAreNotPresentedAsTheSameFact() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("Container endpoint: \$ip:\$port"))
        assertTrue(guide.contains("preferredHostAddress(containerIpv4.toSet())"))
        assertTrue(guide.contains("Android host IPv4 (\${host.interfaceName}): \${host.address}"))
        assertFalse(guide.contains("Detected Android LAN"))
    }

    @Test
    fun restartRecoveryUsesActualLocalAndRemotePorts() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("adb forward --remove tcp:\$effectiveLocalPort"))
        assertTrue(guide.contains("adb forward tcp:\$effectiveLocalPort tcp:\$port"))
        assertTrue(guide.contains("127.0.0.1:\$effectiveLocalPort"))
        assertTrue(guide.contains("wait for the ready confirmation"))
        assertTrue(guide.contains("does not stop TigerVNC"))
        assertFalse(guide.contains("adb forward --remove tcp:5901"))
    }

    @Test
    fun failedVncStartPrintsRecoveryWhileTheManagerRemainsFixedToX0() {
        val access = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        val fixedX11Preparation = access.indexOf("ensureFixedIntegratedX11StoppedForVnc")
        val recoveryIndex = access.indexOf("VncConnectionGuide.logAdbForwardRestartRecovery")

        assertTrue(fixedX11Preparation >= 0)
        assertTrue(recoveryIndex > fixedX11Preparation)
        assertTrue(access.contains("localPort = vncAdbLocalPort"))
        assertTrue(access.contains("onlyIfTroubleshooting = true"))
        assertTrue(access.contains("Constants.X11_DISPLAY"))
    }
}
