package com.unihub.app.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.backup.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _status.value = null
            backupRepository.export(uri)
                .onSuccess { count -> _status.value = "تم تصدير النسخة الاحتياطية ($count عنصراً)" }
                .onFailure { e -> _status.value = "فشل التصدير: ${e.message}" }
            _busy.value = false
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            _status.value = null
            backupRepository.import(uri)
                .onSuccess { count ->
                    _status.value = "تم استيراد $count عنصراً وأُعيدت جدولة التذكيرات"
                }
                .onFailure { e -> _status.value = "فشل الاستيراد: ${e.message}" }
            _busy.value = false
        }
    }
}
