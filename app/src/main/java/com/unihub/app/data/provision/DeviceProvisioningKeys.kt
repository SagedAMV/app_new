package com.unihub.app.data.provision

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

/** Call only on IO. Older Android versions cannot use a Keystore ECDH key directly. */
internal object DeviceProvisioningKeys {
    private const val ALIAS_PREFIX = "unihub_receive_ec_v1_"

    data class State(
        val request: ProvisioningProtocol.ReceiveRequest,
        val alias: String,
        val softwarePrivateKey: String
    ) {
        val usesKeystoreAgreement: Boolean get() = alias.isNotBlank()
        override fun toString(): String = "ReceiveKeyState(request=${request.requestId}, privateMaterial=<redacted>)"
        fun toJson(): String = JSONObject().apply {
            put("request", JSONObject(request.toJson()))
            put("alias", alias)
            put("privateKey", softwarePrivateKey)
        }.toString()
    }

    fun create(account: String, bucket: String): State {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alias = ALIAS_PREFIX + UUID.randomUUID()
            try {
                val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(ProvisioningProtocol.CURVE)).build())
                val pair = generator.generateKeyPair()
                // Exercise the provider, not just key generation. The probe is never a transport key.
                javax.crypto.KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
                    init(pair.private)
                    doPhase(pair.public, true)
                    generateSecret().fill(0)
                }
                return State(ProvisioningProtocol.newRequest(pair.public, account, bucket), alias, "")
            } catch (_: Exception) {
                // Some devices lack P-384 agreement support. Fallback private material is encrypted
                // by the preferences vault, never stored raw or exposed in a receive QR.
                deleteAlias(alias)
            }
        }
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(ProvisioningProtocol.CURVE))
        val pair = generator.generateKeyPair()
        val encoded = pair.private.encoded
        return try {
            State(ProvisioningProtocol.newRequest(pair.public, account, bucket), "", ProvisioningProtocol.encode(encoded))
        } finally { encoded.fill(0) }
    }

    fun read(raw: String): State {
        require(raw.length <= 8_192)
        val root = JSONObject(raw)
        val request = ProvisioningProtocol.readRequest(root.getJSONObject("request").toString())
        val alias = root.optString("alias")
        require(alias.isBlank() || validAlias(alias))
        val privateKey = root.optString("privateKey")
        require((alias.isNotBlank()) xor (privateKey.isNotBlank()))
        return State(request, alias, privateKey)
    }

    fun privateKey(state: State): PrivateKey {
        if (state.alias.isNotBlank()) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return store.getKey(state.alias, null) as? PrivateKey ?: throw IOException("مفتاح الاستقبال لم يعد موجودًا؛ أنشئ طلبًا جديدًا")
        }
        val bytes = ProvisioningProtocol.decode(state.softwarePrivateKey)
        return try { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes)) }
        finally { bytes.fill(0) }
    }

    fun deleteStateKey(raw: String) {
        val alias = runCatching { JSONObject(raw).optString("alias") }.getOrNull().orEmpty()
        if (validAlias(alias)) deleteAlias(alias)
    }

    private fun validAlias(alias: String) = alias.startsWith(ALIAS_PREFIX) && alias.length <= 90 &&
        alias.removePrefix(ALIAS_PREFIX).matches(Regex("[0-9a-f-]{36}"))

    private fun deleteAlias(alias: String) {
        if (!validAlias(alias)) return
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}
