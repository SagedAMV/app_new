package com.unihub.app.core.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts cloud credentials at rest with a non-exportable Android Keystore key. */
internal class CredentialSecretVault {
    fun encode(plainText: String): String {
        if (plainText.isEmpty()) return ""
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + encrypted
            return PREFIX + Base64.getEncoder().encodeToString(combined)
        } catch (error: Exception) {
            throw IOException("تعذّر حفظ بيانات السحابة بشكل مشفّر", error)
        }
    }

    /** Existing plaintext values are returned temporarily so they can be migrated in place. */
    fun decodeOrLegacy(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        return try {
            val combined = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (combined.size <= IV_BYTES) throw IOException("بيانات اعتماد السحابة المخزنة غير صالحة")
            val iv = combined.copyOfRange(0, IV_BYTES)
            val encrypted = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (error: Exception) {
            // A lost/invalid Android Keystore key must never silently fall back to plaintext.
            throw IOException("تعذّر فك تشفير بيانات السحابة؛ أدخل المفاتيح من جديد", error)
        }
    }

    fun isEncoded(value: String): Boolean = value.startsWith(PREFIX)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IOException("مفتاح التشفير المحلي غير موجود")
    }

    private companion object {
        const val PREFIX = "keystore:v1:"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "unihub_r2_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
