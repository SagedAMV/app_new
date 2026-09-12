package com.unihub.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.core.prefs.ThemePreferenceManager
import com.unihub.app.data.repository.DataMaintenanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferenceManager: ThemePreferenceManager,
    private val dataMaintenanceRepository: DataMaintenanceRepository
) : ViewModel() {

    val messenger = UiMessenger()

    val themeMode: StateFlow<ThemeMode> = themePreferenceManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val useDynamicColor: StateFlow<Boolean> = themePreferenceManager.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferenceManager.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { themePreferenceManager.setDynamicColor(enabled) }
    }

    fun clearAllData() {
        viewModelScope.launch {
            runCatching { dataMaintenanceRepository.clearAllData() }
                .onSuccess { messenger.notify("مُسحت جميع البيانات بنجاح") }
                .onFailure { messenger.notifyError("فشل مسح البيانات") }
        }
    }
}
