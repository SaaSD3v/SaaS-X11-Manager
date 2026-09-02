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
    fun restartRecoveryUsesTheActualConfiguredPort() {
        val guide = source("app/src/main/java/com/saas/x11manager/util/VncConnectionGuide.kt")

        assertTrue(guide.contains("adb forward --remove tcp:\$port"))
        assertTrue(guide.contains("adb forward tcp:\$port tcp:\$port"))
        assertTrue(guide.contains("127.0.0.1:\$port"))
        assertTrue(guide.contains("wait until the Manager reports VNC ready"))
        assertTrue(guide.contains("removes only the PC-side ADB forward"))
        assertFalse(guide.contains("adb forward --remove tcp:5901"))
    }

    @Test
    fun failedVncStartsAlsoPrintRecoveryOrdering() {
        val access = source("app/src/main/java/com/saas/x11manager/util/SessionAccessManager.kt")
        val calls = Regex("VncConnectionGuide\\.logAdbForwardRestartRecovery").findAll(access).count()

        assertTrue(calls >= 2)
        assertTrue(access.contains("onlyIfTroubleshooting = true"))
    }
}
