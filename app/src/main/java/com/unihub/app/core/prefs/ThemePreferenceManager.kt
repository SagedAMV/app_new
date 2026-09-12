package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** وضع المظهر — ثلاث حالات بدل ثنائية (فاتح/داكن) في التطبيق المرجعي */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore(name = "unihub_settings")

/**
 * مصدر وحيد لتفضيلات المظهر. تُحقن كـ Singleton عبر Hilt حتى لا يُفتح
 * أكثر من DataStore لنفس الملف (خطأ شائع يسبب IllegalStateException).
 */
@Singleton
class ThemePreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeModeKey]?.let { value ->
            ThemeMode.entries.firstOrNull { it.name == value }
        } ?: ThemeMode.SYSTEM
    }

    val useDynamicColor: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[dynamicColorKey] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeModeKey] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[dynamicColorKey] = enabled }
    }
}
