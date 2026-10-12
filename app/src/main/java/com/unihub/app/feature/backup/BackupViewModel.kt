package com.unihub.app.feature.backup

import com.unihub.app.data.cloud.CloudFailureMessages
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.backup.BackupRepository
import com.unihub.app.data.cloud.CloudSyncManager
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
    // إصلاح: رسائل الفشل كانت تُعرض بلون النجاح نفسه (primary) فلا ينتبه المستخدم أنها فشلت
    private val _statusIsError = MutableStateFlow(false)
    val statusIsError = _statusIsError.asStateFlow()

    private fun showStatus(message: String?, isError: Boolean = false) {
        _statusIsError.value = isError
        _status.value = message
    }
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
        showStatus(null)
        cloudSyncManager.syncWithServer(true)
            .onSuccess { showStatus(it) }
            .onFailure { showStatus(CloudFailureMessages.userMessage(it), isError = true) }
    }

    fun pushToCloud() = runBusy {
        cloudSyncManager.pushToServer()
            .onSuccess { showStatus("تم تحديث بيانات الخادم ($it عنصر) دون حذف الملفات غير المنزّلة") }
            .onFailure { showStatus(CloudFailureMessages.userMessage(it), isError = true) }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            cloudSyncPreferences.setAutoSyncEnabled(enabled)
            if (enabled && cloudSyncManager.checkIsOnline()) cloudSyncManager.syncWithServer()
        }
    }


    fun exportTo(uri: Uri) = runBusy {
        backupRepository.export(uri)
            .onSuccess { showStatus("تم تصدير النسخة المحلية ($it عنصر)") }
            .onFailure { showStatus("فشل التصدير: " + CloudFailureMessages.or(it, "تعذّرت كتابة الملف"), isError = true) }
    }

    fun importFrom(uri: Uri) = runBusy {
        backupRepository.import(uri)
            .onSuccess { showStatus("تم استيراد $it عنصر وأُعيدت جدولة التذكيرات") }
            .onFailure { showStatus("فشل الاستيراد: " + CloudFailureMessages.or(it, "الملف ليس نسخة احتياطية صالحة"), isError = true) }
    }
}

