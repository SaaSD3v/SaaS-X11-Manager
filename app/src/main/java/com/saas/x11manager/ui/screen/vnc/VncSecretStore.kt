package com.saas.x11manager.ui.screen.vnc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed storage for optional remembered VNC passwords.
 *
 * Profile metadata remains in the normal launcher preferences, but credentials
 * never do. The AES key is non-exportable and owned by AndroidKeyStore; only the
 * random IV and authenticated ciphertext are persisted by the app.
 */
internal class VncSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun write(profileId: String, password: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
        val payload = listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        ).joinToString(":")
        check(prefs.edit().putString(entry(profileId), payload).commit())
    }.isSuccess

    @Synchronized
    fun read(profileId: String): String? {
        val payload = prefs.getString(entry(profileId), null) ?: return null
        return runCatching {
            val pieces = payload.split(':', limit = 3)
            require(pieces.size == 3 && pieces[0] == VERSION)
            val iv = Base64.decode(pieces[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(pieces[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun remove(profileId: String) {
        prefs.edit().remove(entry(profileId)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun entry(profileId: String): String = "profile:$profileId"

    private companion object {
        const val PREFS = "standalone-vnc-secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "saas_x11_manager_vnc_passwords_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = "v1"
    }
}
