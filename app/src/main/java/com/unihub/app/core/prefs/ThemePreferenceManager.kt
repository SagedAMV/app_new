package com.unihub.app.core.prefs

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** وضع المظهر — ثلاث حالات بدل ثنائية (فاتح/داكن) في التطبيق المرجعي */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore(name = "unihub_settings")

/**
 * مصدر وحيد لتفضيلات المظهر. تُحقن كـ Singleton عبر Hilt حتى لا يُفتح
 * أكثر من DataStore لنفس الملف (خطأ شائع يسبب IllegalStateException).
 *
 * تحصين ضد كراش الإقلاع: القراءة تمر عبر [safeData] — ملف تفضيلات تالف
 * (قطع كتابة، ترقية بِناء، قرص ممتلئ) كان يرمي `CorruptionException`/`IOException`
 * قبل ظهور أول إطار فيُغلق التطبيق. الآن تُعاد الافتراضيات ويُحذف الملف التالف
 * بأمان لتبدأ القراءة التالية نظيفة.
 */
@Singleton
class ThemePreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")

    /** قراءة محصّنة: أي فشل في قراءة الملف يعيد تفضيلات فارغة بدل الاستثناء */
    private val safeData: Flow<Preferences> = context.dataStore.data
        .catch { error ->
            if (error is CorruptionException || error is IOException) {
                Log.w(TAG, "ملف التفضيلات غير قابل للقراءة — استخدام الافتراضيات", error)
                deleteCorruptFile()
                emit(emptyPreferences())
            } else {
                throw error
            }
        }

    val themeMode: Flow<ThemeMode> = safeData.map { prefs ->
        prefs[themeModeKey]?.let { value ->
            ThemeMode.entries.firstOrNull { it.name == value }
        } ?: ThemeMode.SYSTEM
    }

    val useDynamicColor: Flow<Boolean> = safeData.map { prefs ->
        prefs[dynamicColorKey] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        safeEdit { it[themeModeKey] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        safeEdit { it[dynamicColorKey] = enabled }
    }

    /**
     * كتابة مع تضميد ذاتي: إن فشل الحفظ (ملف تالف) نحذف الملف ونعيد المحاولة
     * مرة واحدة — والفشل الثاني يُسجَّل فقط بدل أن يُسقط التطبيق.
     */
    private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit) {
        runCatching { context.dataStore.edit { transform(it) } }
            .onFailure { firstError ->
                Log.w(TAG, "تعذّر حفظ التفضيلات — محاولة استشفاء", firstError)
                deleteCorruptFile()
                runCatching { context.dataStore.edit { transform(it) } }
                    .onFailure { Log.e(TAG, "تعذّر حفظ التفضيلات حتى بعد الاستشفاء", it) }
            }
    }

    /** حذف ملف التفضيلات التالف — القراءة التالية ستبدأ فارغة وسليمة */
    private fun deleteCorruptFile() {
        runCatching {
            File(context.filesDir, "datastore/$PREFS_FILE_NAME").delete()
        }
    }

    private companion object {
        const val TAG = "ThemePreferenceManager"
        const val PREFS_FILE_NAME = "unihub_settings.preferences_pb"
    }
}
