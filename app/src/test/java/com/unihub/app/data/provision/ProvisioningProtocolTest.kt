package com.unihub.app.data.provision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class ProvisioningProtocolTest {
    private val now = 1_790_000_000_000L
    private val account = "0".repeat(32)
    private val bucket = "test-bucket"
    // Synthetic, intentionally non-secret fixture values; no network requests are made.
    private val credentials = ProvisioningProtocol.PermanentCredentials(account, bucket,
        "TEST_ACCESS_NOT_A_SECRET", "TEST_SECRET_NOT_A_REAL_CLOUD_KEY")

    private fun pair(curve: String = ProvisioningProtocol.CURVE): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    private fun reject(block: () -> Unit) {
        try { block(); fail("Invalid package was accepted") } catch (_: Exception) { }
    }

    @Test
    fun encryptedRoundTripPreservesOriginalLongLivedKeys() {
        val receiver = pair()
        val sender = pair()
        val request = ProvisioningProtocol.newRequest(receiver.public, account, bucket, now)
        val envelope = ProvisioningProtocol.seal(request, credentials, sender.private, sender.public, now)
        assertFalse(envelope.toQr().contains(credentials.secretAccessKey))
        assertFalse(envelope.toJson().contains(credentials.accessKeyId))
        assertEquals(credentials, ProvisioningProtocol.open(ProvisioningProtocol.readEnvelopeQr(envelope.toQr()), request, receiver.private, now))
        assertFalse(credentials.toJson().contains("expiresAt"))
        assertFalse(credentials.toJson().contains("sessionToken"))
    }

    @Test
    fun requestQrContainsOnlyPublicMaterial() {
        val receiver = pair()
        val request = ProvisioningProtocol.newRequest(receiver.public, account, bucket, now)
        assertEquals(request, ProvisioningProtocol.readRequestQr(request.toQr(), now))
        assertFalse(request.toJson().contains("privateKey"))
        assertFalse(request.toJson().contains("secretAccessKey"))
        assertEquals(32, ProvisioningProtocol.keyFingerprint(request).length)
    }

    @Test
    fun anotherDeviceCannotOpenTheEnvelope() {
        val receiver = pair()
        val sender = pair()
        val request = ProvisioningProtocol.newRequest(receiver.public, account, bucket, now)
        val envelope = ProvisioningProtocol.seal(request, credentials, sender.private, sender.public, now)
        reject { ProvisioningProtocol.open(envelope, request, pair().private, now) }
    }

    @Test
    fun modifyingCiphertextOrMetadataIsRejected() {
        val receiver = pair()
        val sender = pair()
        val request = ProvisioningProtocol.newRequest(receiver.public, account, bucket, now)
        val envelope = ProvisioningProtocol.seal(request, credentials, sender.private, sender.public, now)
        val cipher = ProvisioningProtocol.decode(envelope.ciphertext)
        cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        reject { ProvisioningProtocol.open(envelope.copy(ciphertext = ProvisioningProtocol.encode(cipher)), request, receiver.private, now) }
        reject { ProvisioningProtocol.open(envelope.copy(expiresAt = envelope.expiresAt + 1), request, receiver.private, now) }
        reject { ProvisioningProtocol.open(envelope.copy(senderPublicKey = ProvisioningProtocol.encode(pair().public.encoded)), request, receiver.private, now) }
    }

    @Test
    fun expiredOrOverlongRequestCannotBeUsed() {
        val request = ProvisioningProtocol.newRequest(pair().public, account, bucket, now)
        reject { ProvisioningProtocol.readRequestQr(request.toQr(), request.expiresAt) }
        reject { ProvisioningProtocol.validate(request.copy(expiresAt = now + ProvisioningProtocol.REQUEST_LIFETIME_MS + 1), now) }
    }

    @Test
    fun wrongCurveAndPlainCredentialQrAreRejected() {
        reject { ProvisioningProtocol.newRequest(pair("secp256r1").public, account, bucket, now) }
        reject { ProvisioningProtocol.readEnvelopeQr(credentials.toJson()) }
        reject { ProvisioningProtocol.readRequestQr("https://example.invalid/keys", now) }
    }

    @Test
    fun schemasAndBoundsAreStrict() {
        val request = ProvisioningProtocol.newRequest(pair().public, account, bucket, now)
        reject { ProvisioningProtocol.readRequest(JSONObject(request.toJson()).put("version", 9).toString(), now) }
        reject { ProvisioningProtocol.readRequestQr(request.toQr() + "=", now) }
        reject { ProvisioningProtocol.readRequestQr("x".repeat(ProvisioningProtocol.MAX_QR_CHARS + 1), now) }
        reject { ProvisioningProtocol.validate(request.copy(accountId = "not-an-account"), now) }
    }

    @Test
    fun anEnvelopeCannotSilentlyChangeTheCloudTenant() {
        val receiver = pair()
        val sender = pair()
        val request = ProvisioningProtocol.newRequest(receiver.public, account, bucket, now)
        reject { ProvisioningProtocol.seal(request, credentials.copy(accountId = "1".repeat(32)), sender.private, sender.public, now) }
    }

    @Test
    fun diagnosticStringDoesNotExposeEitherKey() {
        assertTrue(credentials.toString().contains("<redacted>"))
        assertFalse(credentials.toString().contains(credentials.accessKeyId))
        assertFalse(credentials.toString().contains(credentials.secretAccessKey))
    }
}
