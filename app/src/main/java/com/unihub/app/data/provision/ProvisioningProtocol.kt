package com.unihub.app.data.provision

import org.json.JSONObject
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Portable wire format shared with the provisioning API. No plaintext credential QR is accepted. */
object ProvisioningProtocol {
    const val REQUEST_PREFIX = "unihub-receive:v1:"
    const val ENVELOPE_PREFIX = "unihub-sealed:v1:"
    const val REQUEST_LIFETIME_MS = 15L * 60 * 1_000
    const val MAX_QR_CHARS = 4_096
    const val CURVE = "secp384r1"
    private const val MAX_JSON_BYTES = 3_072
    private val random = SecureRandom()

    data class ReceiveRequest(
        val requestId: String,
        val publicKey: String,
        val expiresAt: Long,
        val accountId: String,
        val bucketName: String
    ) {
        fun toJson(): String = JSONObject().apply {
            put("type", "unihub-receive")
            put("version", 1)
            put("requestId", requestId)
            put("publicKey", publicKey)
            put("expiresAt", expiresAt)
            put("accountId", accountId)
            put("bucketName", bucketName)
        }.toString()
        fun toQr(): String = REQUEST_PREFIX + encode(toJson().toByteArray(Charsets.UTF_8))
    }

    data class Envelope(
        val requestId: String,
        val receiverKeyHash: String,
        val senderPublicKey: String,
        val expiresAt: Long,
        val iv: String,
        val ciphertext: String
    ) {
        fun toJson(): String = JSONObject().apply {
            put("type", "unihub-sealed")
            put("version", 1)
            put("requestId", requestId)
            put("receiverKeyHash", receiverKeyHash)
            put("senderPublicKey", senderPublicKey)
            put("expiresAt", expiresAt)
            put("iv", iv)
            put("ciphertext", ciphertext)
        }.toString()
        fun toQr(): String = ENVELOPE_PREFIX + encode(toJson().toByteArray(Charsets.UTF_8))
    }

    data class PermanentCredentials(
        val accountId: String,
        val bucketName: String,
        val accessKeyId: String,
        val secretAccessKey: String
    ) {
        fun toJson(): String = JSONObject().apply {
            put("type", "r2-permanent")
            put("accountId", accountId)
            put("bucketName", bucketName)
            put("accessKeyId", accessKeyId)
            put("secretAccessKey", secretAccessKey)
        }.toString()
        override fun toString(): String = "PermanentCredentials(account=$accountId, bucket=$bucketName, secrets=<redacted>)"
    }

    fun newRequest(publicKey: PublicKey, accountId: String, bucketName: String, now: Long = System.currentTimeMillis()): ReceiveRequest =
        ReceiveRequest(encode(ByteArray(32).also(random::nextBytes)), encode(publicKey.encoded),
            now + REQUEST_LIFETIME_MS, accountId, bucketName).also { validate(it, now) }

    fun readRequestQr(qr: String, now: Long = System.currentTimeMillis()): ReceiveRequest = readRequest(readQr(qr, REQUEST_PREFIX), now)

    fun readRequest(json: String, now: Long = System.currentTimeMillis()): ReceiveRequest {
        val root = readRoot(json, "unihub-receive")
        return ReceiveRequest(text(root, "requestId"), text(root, "publicKey"), integer(root, "expiresAt"),
            text(root, "accountId"), text(root, "bucketName")).also { validate(it, now) }
    }

    fun validate(request: ReceiveRequest, now: Long = System.currentTimeMillis()) {
        require(decode(request.requestId).size == 32) { "معرّف طلب الاستلام غير صالح" }
        require(request.expiresAt > now && request.expiresAt - now <= REQUEST_LIFETIME_MS) { "طلب الاستلام منتهٍ؛ أنشئ طلبًا جديدًا" }
        require(request.accountId.matches(Regex("[0-9a-f]{32}"))) { "حساب السحابة غير صالح" }
        require(validBucket(request.bucketName)) { "الحاوية غير صالحة" }
        publicKey(request.publicKey)
    }

    fun readEnvelopeQr(qr: String): Envelope = readEnvelope(readQr(qr, ENVELOPE_PREFIX))

    fun readEnvelope(json: String): Envelope {
        val root = readRoot(json, "unihub-sealed")
        return Envelope(text(root, "requestId"), text(root, "receiverKeyHash"), text(root, "senderPublicKey"),
            integer(root, "expiresAt"), text(root, "iv"), text(root, "ciphertext")).also {
            require(decode(it.requestId).size == 32 && decode(it.receiverKeyHash).size == 32)
            require(decode(it.iv).size == 12 && decode(it.ciphertext).size in 17..2_048)
            publicKey(it.senderPublicKey)
        }
    }

    fun seal(
        request: ReceiveRequest,
        credentials: PermanentCredentials,
        senderPrivateKey: PrivateKey,
        senderPublicKey: PublicKey,
        now: Long = System.currentTimeMillis()
    ): Envelope {
        validate(request, now)
        validateCredentials(credentials)
        require(credentials.accountId == request.accountId && credentials.bucketName == request.bucketName) { "الحزمة ليست للسحابة المطلوبة" }
        val iv = ByteArray(12).also(random::nextBytes)
        val shell = Envelope(request.requestId, hashKey(request.publicKey), encode(senderPublicKey.encoded), request.expiresAt, encode(iv), "")
        val key = deriveKey(senderPrivateKey, publicKey(request.publicKey), decode(request.requestId), aad(shell))
        val plain = credentials.toJson().toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(shell))
            shell.copy(ciphertext = encode(cipher.doFinal(plain)))
        } finally {
            key.fill(0)
            plain.fill(0)
        }
    }

    fun open(
        envelope: Envelope,
        request: ReceiveRequest,
        receiverPrivateKey: PrivateKey,
        now: Long = System.currentTimeMillis()
    ): PermanentCredentials {
        validate(request, now)
        require(envelope.requestId == request.requestId && envelope.expiresAt == request.expiresAt &&
            envelope.receiverKeyHash == hashKey(request.publicKey)) { "الحزمة موجهة لطلب استقبال أو جهاز آخر" }
        require(decode(envelope.iv).size == 12 && decode(envelope.ciphertext).size in 17..2_048)
        val key = deriveKey(receiverPrivateKey, publicKey(envelope.senderPublicKey), decode(request.requestId), aad(envelope))
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decode(envelope.iv)))
                updateAAD(aad(envelope))
                doFinal(decode(envelope.ciphertext))
            }
        } finally { key.fill(0) }
        return try {
            val root = JSONObject(String(plain, Charsets.UTF_8))
            require(text(root, "type") == "r2-permanent")
            PermanentCredentials(text(root, "accountId"), text(root, "bucketName"), text(root, "accessKeyId"),
                text(root, "secretAccessKey")).also {
                validateCredentials(it)
                require(it.accountId == request.accountId && it.bucketName == request.bucketName)
            }
        } finally { plain.fill(0) }
    }

    fun publicKey(encoded: String): PublicKey {
        val bytes = decode(encoded)
        require(bytes.size in 100..160) { "مفتاح الاستقبال العام غير صالح" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as? ECPublicKey
            ?: error("نوع مفتاح الاستقبال غير مدعوم")
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVE)) }
            .getParameterSpec(ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor) { "منحنى التشفير غير مدعوم" }
        return key
    }

    fun keyFingerprint(request: ReceiveRequest): String = MessageDigest.getInstance("SHA-256")
        .digest(decode(request.publicKey)).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun hashKey(encoded: String): String = encode(MessageDigest.getInstance("SHA-256").digest(decode(encoded)))

    private fun deriveKey(privateKey: PrivateKey, publicKey: PublicKey, salt: ByteArray, info: ByteArray): ByteArray {
        val agreement = if (privateKey.format == null) KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
            else KeyAgreement.getInstance("ECDH")
        val shared = agreement.run { init(privateKey); doPhase(publicKey, true); generateSecret() }
        val prk = try { hmac(salt, shared) } finally { shared.fill(0) }
        return try { hmac(prk, info + byteArrayOf(1)).copyOf(32) } finally { prk.fill(0) }
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA384").run {
        init(SecretKeySpec(key, "HmacSHA384")); doFinal(input)
    }

    private fun aad(envelope: Envelope): ByteArray = (
        "unihub-r2-envelope-v1|${envelope.requestId}|${envelope.expiresAt}|${envelope.receiverKeyHash}|${envelope.senderPublicKey}"
    ).toByteArray(Charsets.UTF_8)

    private fun readRoot(json: String, type: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "حزمة الاتصال أكبر من المسموح" }
        return JSONObject(json).also { require(text(it, "type") == type && integer(it, "version") == 1L) { "حزمة غير مدعومة" } }
    }

    private fun readQr(raw: String, prefix: String): String {
        require(raw.length <= MAX_QR_CHARS && raw.startsWith(prefix)) { "هذا ليس باركود اتصال مشفرًا معتمدًا" }
        val bytes = decode(raw.removePrefix(prefix))
        require(bytes.size <= MAX_JSON_BYTES)
        return String(bytes, Charsets.UTF_8)
    }

    private fun text(root: JSONObject, field: String): String = root.opt(field) as? String ?: error("حقل $field غير صالح")
    private fun integer(root: JSONObject, field: String): Long {
        val value = root.opt(field) as? Number ?: error("حقل $field غير صالح")
        val number = value.toLong()
        require(value.toDouble() == number.toDouble())
        return number
    }

    private fun validateCredentials(value: PermanentCredentials) {
        require(value.accountId.matches(Regex("[0-9a-f]{32}")) && validBucket(value.bucketName))
        require(value.accessKeyId.isNotBlank() && value.accessKeyId.length <= 256 && !value.accessKeyId.any(Char::isISOControl))
        require(value.secretAccessKey.isNotBlank() && value.secretAccessKey.length <= 512 && !value.secretAccessKey.any(Char::isISOControl))
    }

    private fun validBucket(value: String) = value.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in value
    internal fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    internal fun decode(encoded: String): ByteArray {
        require(encoded.length <= MAX_QR_CHARS && encoded.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.getUrlDecoder().decode(encoded).also { require(encode(it) == encoded) }
    }
}
