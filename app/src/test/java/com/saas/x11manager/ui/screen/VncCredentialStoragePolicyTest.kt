package com.saas.x11manager.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VncCredentialStoragePolicyTest {
    private fun source(relativePath: String): String {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile
        }
        error("Could not locate $relativePath")
    }

    @Test
    fun rememberedPasswordsUseAndroidKeystoreAndNeverProfileJson() {
        val launcher = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/vnc/VncLauncherViewModel.kt"
        )
        val secrets = source(
            "app/src/main/java/com/saas/x11manager/ui/screen/vnc/VncSecretStore.kt"
        )

        assertTrue(launcher.contains("private val secrets = VncSecretStore(context)"))
        assertTrue(launcher.contains("secrets.write(profile.id, profile.password)"))
        assertTrue(launcher.contains("secrets.read(id)"))
        assertTrue(launcher.contains("metadataNeedsSanitizing"))
        assertFalse(launcher.contains("put(\"password\""))

        assertTrue(secrets.contains("AndroidKeyStore"))
        assertTrue(secrets.contains("AES/GCM/NoPadding"))
        assertTrue(secrets.contains("setRandomizedEncryptionRequired(true)"))
    }
}
