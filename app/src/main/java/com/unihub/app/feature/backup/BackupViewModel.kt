package com.unihub.app.feature.backup

import com.unihub.app.data.cloud.CloudFailureMessages
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.backup.BackupRepository
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.R2Credentials
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val cloudSyncManager: CloudSyncManager,
    private val cloudSyncPreferences: CloudSyncPreferences,
    private val authManager: CloudAuthManager
) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val isOnline = cloudSyncManager.isOnline
    val isSyncing = cloudSyncManager.isSyncing
    val authSession = authManager.sessionState
    // السحابة هنا: ما تحتاجه هذه الشاشة (حالة الاتصال/المزامنة وإعدادات الخادم
    // وعدد الملفات الجديدة للزر والبانر) — التصفح والتنزيل انتقلا إلى الشاشة
    // المستقلة CloudFilesScreen.
    // جولة تعليمات.md: أُزيلت خاصية availableRemoteFiles من هنا بعد توحيد نقاط فتح
    // السحابة؛ مستهلكوها السابقون (الشارة والبانر وزر الاختيار) أزيلوا من الشاشة.
    val cloudSettings = cloudSyncPreferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun runBusy(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() } finally { _busy.value = false }
        }
    }

    fun syncWithCloud() = runBusy {
        _status.value = null
        cloudSyncManager.syncWithServer(true)
            .onSuccess { _status.value = it }
            .onFailure { _status.value = CloudFailureMessages.userMessage(it) }
    }

    fun pushToCloud() = runBusy {
        cloudSyncManager.pushToServer()
            .onSuccess { _status.value = "تم تحديث بيانات الخادم ($it عنصر) دون حذف الملفات غير المنزّلة" }
            .onFailure { _status.value = CloudFailureMessages.userMessage(it) }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            cloudSyncPreferences.setAutoSyncEnabled(enabled)
            if (enabled && cloudSyncManager.checkIsOnline()) cloudSyncManager.syncWithServer()
        }
    }

    fun saveCloudCredentials(accountId: String, endpointUrl: String, bucketName: String, accessKeyId: String, secretAccessKey: String) {
        viewModelScope.launch {
            authManager.requireAdmin().getOrElse {
                _status.value = CloudFailureMessages.or(it, "تعديل إعدادات الخادم السحابي متاح للمشرف فقط")
                return@launch
            }
            runCatching {
                val existing = cloudSyncPreferences.snapshot().credentials
                val creds = R2Credentials(
                    accountId = accountId.trim(),
                    endpointUrl = endpointUrl.trim(),
                    bucketName = bucketName.trim(),
                    accessKeyId = accessKeyId.trim().ifBlank { existing.accessKeyId },
                    secretAccessKey = secretAccessKey.trim().ifBlank { existing.secretAccessKey }
                )
                require(creds.isConfigured) {
                    "إعدادات R2 غير صالحة. تأكد من معرّف الحساب والحاوية والمفتاحين، واستخدم نقطة النهاية الرسمية لحساب Cloudflare نفسه."
                }
                cloudSyncPreferences.saveCredentials(creds)
            }.onSuccess {
                _status.value = "تم حفظ إعدادات R2 مشفّرة محلياً"
            }.onFailure {
                _status.value = CloudFailureMessages.userMessage(it)
            }
        }
    }

    fun exportTo(uri: Uri) = runBusy {
        backupRepository.export(uri)
            .onSuccess { _status.value = "تم تصدير النسخة المحلية ($it عنصر)" }
            .onFailure { _status.value = "فشل التصدير: ${it.message}" }
    }

    fun importFrom(uri: Uri) = runBusy {
        backupRepository.import(uri)
            .onSuccess { _status.value = "تم استيراد $it عنصر وأُعيدت جدولة التذكيرات" }
            .onFailure { _status.value = "فشل الاستيراد: ${it.message}" }
    }
}
