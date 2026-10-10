package com.unihub.app.feature.auth

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.auth.AuthSessionState
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.provision.CloudProvisioningManager
import com.unihub.app.data.provision.ProvisioningProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class CloudConnectionUiState(
    val busy: Boolean = false,
    val receiveQr: String? = null,
    val receiverFingerprint: String? = null,
    val exportQr: String? = null,
    val error: String? = null,
    val successVersion: Int = 0
)

@HiltViewModel
class CloudConnectionViewModel @Inject constructor(
    private val manager: CloudProvisioningManager,
    @ApplicationContext private val context: Context,
    authManager: CloudAuthManager
) : ViewModel() {
    private val _state = MutableStateFlow(CloudConnectionUiState())
    val state = _state.asStateFlow()
    val serviceAvailable: Boolean get() = manager.serviceAvailable
    private var job: Job? = null
    private var revision = 0
    private var ownerOperation = false

    init {
        viewModelScope.launch {
            authManager.sessionState.collect { session ->
                if ((session as? AuthSessionState.Authenticated)?.user?.isAdmin != true) {
                    if (ownerOperation) close()
                    _state.update { it.copy(exportQr = null) }
                }
            }
        }
    }

    fun prepare(forceNew: Boolean = false) = runOperation { current ->
        val request = manager.receiveRequest(forceNew)
        updateIfCurrent(current) { it.copy(receiveQr = request.toQr(), receiverFingerprint = ProvisioningProtocol.keyFingerprint(request)) }
    }

    fun receiveCode(code: String) = runOperation { current ->
        manager.receiveFromService(code)
        completed(current)
    }

    fun receiveQr(qr: String) = runOperation { current ->
        if (qr.startsWith(INVITATION_PREFIX)) manager.receiveFromService(qr.removePrefix(INVITATION_PREFIX))
        else manager.receiveFromQr(qr)
        completed(current)
    }

    fun receiveImage(uri: Uri) = runOperation { current ->
        val qr = ConnectionQrImages.decode(context, uri)
        if (qr.startsWith(INVITATION_PREFIX)) manager.receiveFromService(qr.removePrefix(INVITATION_PREFIX))
        else manager.receiveFromQr(qr)
        completed(current)
    }

    fun exportImage(uri: Uri, acknowledgedPermanentAccess: Boolean) = runOperation(owner = true) { current ->
        val requestQr = ConnectionQrImages.decode(context, uri)
        val request = ProvisioningProtocol.readRequestQr(requestQr.trim())
        val qr = manager.exportForDevice(requestQr, acknowledgedPermanentAccess)
        updateIfCurrent(current) { it.copy(exportQr = qr, receiverFingerprint = ProvisioningProtocol.keyFingerprint(request)) }
    }

    fun export(requestQr: String, acknowledgedPermanentAccess: Boolean) = runOperation(owner = true) { current ->
        val request = ProvisioningProtocol.readRequestQr(requestQr.trim())
        val qr = manager.exportForDevice(requestQr, acknowledgedPermanentAccess)
        updateIfCurrent(current) { it.copy(exportQr = qr, receiverFingerprint = ProvisioningProtocol.keyFingerprint(request)) }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    fun close() {
        revision += 1
        job?.cancel()
        ownerOperation = false
        _state.value = CloudConnectionUiState()
    }

    private fun completed(current: Int) = updateIfCurrent(current) {
        it.copy(receiveQr = null, receiverFingerprint = null, successVersion = it.successVersion + 1)
    }

    private fun updateIfCurrent(current: Int, update: (CloudConnectionUiState) -> CloudConnectionUiState) {
        if (revision == current) _state.update(update)
    }

    private fun runOperation(owner: Boolean = false, block: suspend (Int) -> Unit) {
        if (job?.isActive == true) return
        val current = ++revision
        ownerOperation = owner
        _state.update { it.copy(busy = true, error = null, exportQr = if (owner) null else it.exportQr) }
        job = viewModelScope.launch {
            try { block(current) }
            catch (_: TimeoutCancellationException) {
                if (revision == current) _state.update { it.copy(error = "انتهت مهلة الاتصال؛ أعد المحاولة بالطلب نفسه") }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (revision == current) {
                    val message = if (error is IOException && error.message.orEmpty().any { it.code in 0x0600..0x06ff }) error.message
                        else "تعذّر فتح حزمة الاتصال؛ تحقق من المصدر وطلب الجهاز والصلاحية الزمنية"
                    _state.update { it.copy(error = message) }
                }
            }
            finally { if (revision == current) _state.update { it.copy(busy = false) } }
        }
    }

    companion object {
        const val INVITATION_PREFIX = "unihub-invite:v1:"
    }
}
