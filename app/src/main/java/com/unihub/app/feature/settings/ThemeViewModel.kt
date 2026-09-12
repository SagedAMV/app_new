package com.unihub.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.core.prefs.ThemePreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * مصدر المظهر لجذر التطبيق (يُقرأ في [com.unihub.app.UniHubApp]).
 * نفس [ThemePreferenceManager] المُحقون كـ Singleton يمنع تعدد نسخ DataStore.
 */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    preferences: ThemePreferenceManager
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val useDynamicColor: StateFlow<Boolean> = preferences.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
