package com.unihub.app.core.prefs

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.IOException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated at-rest encryption. This does not protect a secret from a compromised running app. */
internal class CredentialSecretVault(private val alias: String = "unihub_local_secrets_v2") {
    fun encode(plainText: String, purpose: String = "r2-credential"): String {
        if (plainText.isEmpty()) return ""
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= MAX_PLAIN_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad(purpose))
            check(cipher.iv.size == IV_BYTES)
            return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(bytes))
        } catch (error: Exception) {
            throw IOException("تعذّر حفظ البيانات المحمية؛ لم تُحفظ نسخة غير مشفرة", error)
        } finally {
            bytes.fill(0)
        }
    }

    /** Legacy plaintext/v1 is read only for an explicit in-place migration on an IO dispatcher. */
    fun decodeOrLegacy(stored: String, purpose: String = "r2-credential"): String {
        if (stored.isEmpty()) return ""
        val legacy = stored.startsWith(LEGACY_PREFIX)
        if (!legacy && !stored.startsWith(PREFIX)) {
            if (stored.startsWith("keystore:")) throw IOException("صيغة التشفير المحلي غير مدعومة")
            return stored
        }
        return try {
            require(stored.length <= MAX_ENCODED_CHARS)
            val prefix = if (legacy) LEGACY_PREFIX else PREFIX
            val combined = Base64.getDecoder().decode(stored.removePrefix(prefix))
            require(combined.size >= IV_BYTES + TAG_BITS / 8)
            val key = existingKey(if (legacy) LEGACY_ALIAS else alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, combined.copyOfRange(0, IV_BYTES)))
            if (!legacy) cipher.updateAAD(aad(purpose))
            val plain = cipher.doFinal(combined, IV_BYTES, combined.size - IV_BYTES)
            try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
        } catch (error: Exception) {
            throw IOException("تعذّر فتح البيانات المحمية على هذا الجهاز؛ أعد استيراد الاتصال من الخدمة أو الباركود", error)
        }
    }

    /** New protected fields have no plaintext migration path. Reject an unsealed replacement. */
    fun decodeProtected(stored: String, purpose: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) throw IOException("بيانات محمية بصيغة غير صالحة؛ أعد الاستيراد أو تسجيل الدخول")
        return decodeOrLegacy(stored, purpose)
    }

    fun isEncoded(value: String): Boolean = value.startsWith(PREFIX) || value.startsWith(LEGACY_PREFIX)
    fun needsMigration(value: String): Boolean = value.isNotBlank() && !value.startsWith(PREFIX)

    private fun aad(purpose: String) = "com.unihub.app::$alias::$purpose::v2".toByteArray(Charsets.UTF_8)

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return@synchronized generateKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // StrongBox is optional and not present on every supported device.
            } catch (_: InvalidAlgorithmParameterException) {
                // Some providers cannot generate this key in StrongBox.
            } catch (_: ProviderException) {
                // Generation can be unsupported; never delete an existing key to recover.
            }
        }
        generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && strongBox) spec.setIsStrongBoxBacked(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(spec.build())
        }.generateKey()
    }

    private fun existingKey(keyAlias: String): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        store.getKey(keyAlias, null) as? SecretKey ?: throw IOException("مفتاح التشفير المحلي غير موجود")
    }

    private companion object {
        val KEY_LOCK = Any()
        const val PREFIX = "keystore:v2:"
        const val LEGACY_PREFIX = "keystore:v1:"
        const val LEGACY_ALIAS = "unihub_r2_credentials_v1"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_PLAIN_BYTES = 256 * 1024
        const val MAX_ENCODED_CHARS = 360 * 1024
    }
}
