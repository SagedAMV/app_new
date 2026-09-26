package com.unihub.app.core.prefs

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** لقطة كاملة لإعدادات النسخ الاحتياطي التلقائي */
data class AutoBackupSettings(
    /** مجلد النسخ الاحتياطية المختار عبر SAF — null يعني غير محدد */
    val treeUri: Uri?,
    /** عدد أيام الفاصل بين كل نسخة دورية (1/3/7/30 أو قيمة مخصصة) */
    val intervalDays: Int,
    /** النص الخام لخانة اليوم المخصص (ما يكتبه المستخدم) */
    val customDaysInput: String,
    /** «نسخة بعد كل عملية تعديل» — خيار التجربة للتحقق من عمل النسخ */
    val backupOnChange: Boolean,
    /** آخر نجاح/فشل مع وقته — للتغذية الراجعة في الإعدادات */
    val lastBackupAt: Long,
    val lastBackupMessage: String
) {
    val isFolderConfigured: Boolean get() = treeUri != null
}

private val Context.autoBackupDataStore by preferencesDataStore(name = "unihub_autobackup")

/**
 * مصدر وحيد لإعدادات النسخ الاحتياطي التلقائي. نمط مطابق لـ
 * [ThemePreferenceManager]: Singleton (ملف DataStore واحد فقط)، قراءة محصّنة
 * ضد الملفات التالفة، وكتابة مع استشفاء ذاتي.
 */
@Singleton
class AutoBackupPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val treeUriKey = stringPreferencesKey("backup_tree_uri")
    private val intervalDaysKey = intPreferencesKey("interval_days")
    private val customDaysInputKey = stringPreferencesKey("custom_days_input")
    private val backupOnChangeKey = booleanPreferencesKey("backup_on_change")
    private val lastBackupAtKey = longPreferencesKey("last_backup_at")
    private val lastBackupMessageKey = stringPreferencesKey("last_backup_message")

    /** قراءة محصّنة: أي فشل يعيد تفضيلات فارغة بدل الاستثناء */
    private val safeData: Flow<Preferences> = context.autoBackupDataStore.data
        .catch { error ->
            if (error is CorruptionException || error is IOException) {
                Log.w(TAG, "ملف تفضيلات النسخ التلقائي تالف — استخدام الافتراضيات", error)
                deleteCorruptFile()
                emit(emptyPreferences())
            } else {
                throw error
            }
        }

    val settings: Flow<AutoBackupSettings> = safeData.map { prefs -> toSettings(prefs) }

    /** لقطة فورية — يستخدمها عامل النسخ الاحتياطي بعيداً عن الواجهة */
    suspend fun snapshot(): AutoBackupSettings = settings.first()

    private fun toSettings(prefs: Preferences): AutoBackupSettings {
        val uriString = prefs[treeUriKey]
        return AutoBackupSettings(
            treeUri = uriString?.takeIf { it.isNotBlank() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            },
            intervalDays = (prefs[intervalDaysKey] ?: DEFAULT_INTERVAL_DAYS)
                .coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS),
            customDaysInput = prefs[customDaysInputKey].orEmpty(),
            backupOnChange = prefs[backupOnChangeKey] ?: false,
            lastBackupAt = prefs[lastBackupAtKey] ?: 0L,
            lastBackupMessage = prefs[lastBackupMessageKey].orEmpty()
        )
    }

    suspend fun setTreeUri(uri: Uri?) {
        safeEdit { prefs ->
            if (uri == null) prefs.remove(treeUriKey) else prefs[treeUriKey] = uri.toString()
        }
    }

    suspend fun setIntervalDays(days: Int) {
        safeEdit { it[intervalDaysKey] = days.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS) }
    }

    suspend fun setCustomDaysInput(raw: String) {
        safeEdit { it[customDaysInputKey] = raw.take(4) }
    }

    suspend fun setBackupOnChange(enabled: Boolean) {
        safeEdit { it[backupOnChangeKey] = enabled }
    }

    /** تسجيل نتيجة آخر محاولة نسخ — تظهر كتغذية راجعة في شاشة الإعدادات */
    suspend fun recordResult(success: Boolean, message: String) {
        safeEdit { prefs ->
            prefs[lastBackupAtKey] = System.currentTimeMillis()
            prefs[lastBackupMessageKey] = (if (success) "" else "فشل: ") + message
        }
    }

    /** كتابة مع تضميد ذاتي — نفس نهج [ThemePreferenceManager] */
    private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit) {
        runCatching { context.autoBackupDataStore.edit { transform(it) } }
            .onFailure { firstError ->
                Log.w(TAG, "تعذّر حفظ تفضيلات النسخ التلقائي — محاولة استشفاء", firstError)
                deleteCorruptFile()
                runCatching { context.autoBackupDataStore.edit { transform(it) } }
                    .onFailure { Log.e(TAG, "تعذّر الحفظ حتى بعد الاستشفاء", it) }
            }
    }

    private fun deleteCorruptFile() {
        runCatching {
            File(context.filesDir, "datastore/$PREFS_FILE_NAME").delete()
        }
    }

    companion object {
        private const val TAG = "AutoBackupPreferences"
        private const val PREFS_FILE_NAME = "unihub_autobackup.preferences_pb"
        const val DEFAULT_INTERVAL_DAYS = 7
        const val MIN_INTERVAL_DAYS = 1
        const val MAX_INTERVAL_DAYS = 365
    }
}
