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

        assertTrue(guide.contains("ADB forward local port selected: \$effectiveLocalPort"))
        assertTrue(guide.contains("USB local endpoint after forward: 127.0.0.1:\$effectiveLocalPort"))
        assertTrue(guide.contains("adb forward tcp:\$effectiveLocalPort tcp:\$port"))
        assertTrue(guide.contains("the Manager does not create PC-side forwards"))
        assertFalse(guide.contains("adb forward tcp:5901 tcp:5901"))
    }

    @Test
    fun connectionGuideOnlyAdvertisesExactUsbTargetAfterHostPortProbe() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("androidHostPortListening = isAndroidHostPortListening(port)"))
        assertTrue(guide.contains("if (runtime.androidHostPortListening)"))
        assertTrue(guide.contains("Android host TCP \$port is not listening"))
        assertTrue(guide.contains("tcp:<hostPort>"))
        assertTrue(guide.contains("/proc/net/tcp /proc/net/tcp6"))
    }

    @Test
    fun restartRecoveryUsesActualLocalAndRemotePorts() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("adb forward --remove tcp:\$effectiveLocalPort"))
        assertTrue(guide.contains("adb forward tcp:\$effectiveLocalPort tcp:\$port"))
        assertTrue(guide.contains("127.0.0.1:\$effectiveLocalPort"))
        assertTrue(guide.contains("wait until the Manager reports VNC ready"))
        assertTrue(guide.contains("does not stop TigerVNC"))
        assertFalse(guide.contains("adb forward --remove tcp:5901"))
    }

    @Test
    fun failedVncStartPrintsRecoveryAndThenRollsBackOnlyItsMonitorReservation() {
        val access = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")

        val recoveryIndex = access.indexOf("VncConnectionGuide.logAdbForwardRestartRecovery")
        val rollbackIndex = access.indexOf("VncX11MonitorReservation.rollbackAfterFailedVncStart")

        assertTrue(recoveryIndex >= 0)
        assertTrue(access.contains("localPort = vncAdbLocalPort"))
        assertTrue(access.contains("onlyIfTroubleshooting = true"))
        assertTrue(rollbackIndex > recoveryIndex)
    }
}
