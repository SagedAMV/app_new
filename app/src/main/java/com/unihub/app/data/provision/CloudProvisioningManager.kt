package com.unihub.app.data.provision

import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.cloud.CloudflareR2Client
import com.unihub.app.data.cloud.CloudflareR2Config
import com.unihub.app.data.cloud.R2Credentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudProvisioningManager @Inject constructor(
    private val preferences: CloudSyncPreferences,
    private val client: CloudProvisioningClient,
    private val r2Client: CloudflareR2Client,
    private val authManager: CloudAuthManager
) {
    private val mutex = Mutex()
    val serviceAvailable: Boolean get() = client.isAvailable

    suspend fun receiveRequest(forceNew: Boolean = false): ProvisioningProtocol.ReceiveRequest = withContext(Dispatchers.IO) {
        mutex.withLock { requestState(forceNew).second.request }
    }

    private suspend fun requestState(forceNew: Boolean): Pair<String, DeviceProvisioningKeys.State> {
        val previous = try { preferences.pairingState() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "" }
        if (!forceNew && previous.isNotBlank()) {
            val state = runCatching { DeviceProvisioningKeys.read(previous) }.getOrNull()
            if (state != null) return previous to state
        }
        val state = DeviceProvisioningKeys.create(CloudflareR2Config.DEFAULT_ACCOUNT_ID, CloudflareR2Config.DEFAULT_BUCKET_NAME)
        val raw = state.toJson()
        try {
            preferences.savePairingState(raw)
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { DeviceProvisioningKeys.deleteStateKey(raw) }
            throw error
        }
        DeviceProvisioningKeys.deleteStateKey(previous)
        return raw to state
    }

    suspend fun receiveFromService(invitation: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireUnauthenticated()
            val (raw, state) = requestState(forceNew = false)
            accept(client.receive(invitation, state.request), raw, state)
        }
    }

    suspend fun receiveFromQr(qr: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireUnauthenticated()
            val raw = preferences.pairingState()
            if (raw.isBlank()) throw IOException("اعرض طلب استقبال من هذا الجهاز أولًا، ثم اطلب من المالك تشفير الحزمة له")
            val state = DeviceProvisioningKeys.read(raw)
            accept(ProvisioningProtocol.readEnvelopeQr(qr.trim()), raw, state)
        }
    }

    private suspend fun accept(envelope: ProvisioningProtocol.Envelope, raw: String, state: DeviceProvisioningKeys.State) {
        val payload = ProvisioningProtocol.open(envelope, state.request, DeviceProvisioningKeys.privateKey(state))
        if (payload.accountId != CloudflareR2Config.DEFAULT_ACCOUNT_ID || payload.bucketName != CloudflareR2Config.DEFAULT_BUCKET_NAME) {
            throw IOException("الحزمة لا تخص حساب وحاوية هذا التطبيق")
        }
        val candidate = R2Credentials(accountId = payload.accountId, endpointUrl = "", bucketName = payload.bucketName,
            accessKeyId = payload.accessKeyId, secretAccessKey = payload.secretAccessKey)
        if (!candidate.isConfigured) throw IOException("بيانات الاتصال المستلمة غير صالحة")
        // Live read-only validation also prevents accepting fabricated credentials for a different cloud.
        r2Client.testConnection(candidate).getOrThrow()
        ProvisioningProtocol.validate(state.request)
        requireUnauthenticated()
        authManager.logout()
        preferences.consumePairingAndSave(candidate, raw)
        withContext(NonCancellable + Dispatchers.IO) { DeviceProvisioningKeys.deleteStateKey(raw) }
    }

    suspend fun exportForDevice(requestQr: String, acknowledgedPermanentAccess: Boolean): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!acknowledgedPermanentAccess) throw IOException("يلزم تأكيد أن المستلم سيحصل على صلاحيات مفتاح R2 الدائم")
            authManager.verifyActiveSessionWithCloud().getOrThrow()
            authManager.requireAdmin().getOrThrow()
            val request = ProvisioningProtocol.readRequestQr(requestQr.trim())
            val credentials = preferences.snapshot().credentials
            if (!credentials.isConfigured) throw IOException("اتصال المالك غير صالح")
            if (request.accountId != credentials.accountId || request.bucketName != credentials.bucketName) {
                throw IOException("طلب الجهاز ليس لحساب وحاوية المالك")
            }
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(ProvisioningProtocol.CURVE))
            val ephemeral = generator.generateKeyPair()
            val envelope = ProvisioningProtocol.seal(request, ProvisioningProtocol.PermanentCredentials(
                credentials.accountId, credentials.bucketName, credentials.accessKeyId, credentials.secretAccessKey
            ), ephemeral.private, ephemeral.public)
            authManager.requireAdmin().getOrThrow()
            envelope.toQr().also { require(it.length <= ProvisioningProtocol.MAX_QR_CHARS) }
        }
    }

    private fun requireUnauthenticated() {
        if (authManager.isAuthenticatedNow()) throw IOException("استيراد الاتصال متاح من شاشة الدخول فقط؛ سجّل الخروج أولًا")
    }
}
