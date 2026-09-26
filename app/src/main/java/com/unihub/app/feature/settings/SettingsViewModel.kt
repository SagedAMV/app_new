package com.unihub.app.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.prefs.AutoBackupPreferences
import com.unihub.app.core.prefs.AutoBackupSettings
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.core.prefs.ThemePreferenceManager
import com.unihub.app.data.backup.AutoBackupExporter
import com.unihub.app.data.backup.AutoBackupScheduler
import com.unihub.app.data.repository.DataMaintenanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themePreferenceManager: ThemePreferenceManager,
    private val dataMaintenanceRepository: DataMaintenanceRepository,
    private val autoBackupPreferences: AutoBackupPreferences,
    private val autoBackupScheduler: AutoBackupScheduler,
    private val autoBackupExporter: AutoBackupExporter
) : ViewModel() {

    val messenger = UiMessenger()

    val themeMode: StateFlow<ThemeMode> = themePreferenceManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val useDynamicColor: StateFlow<Boolean> = themePreferenceManager.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** إعدادات النسخ الاحتياطي التلقائي كاملة — تغذي قسمها في شاشة الإعدادات */
    val autoBackupSettings: StateFlow<AutoBackupSettings?> = autoBackupPreferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferenceManager.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { themePreferenceManager.setDynamicColor(enabled) }
    }

    // ─── النسخ الاحتياطي التلقائي ────────────────────────────────────────

    /**
     * المستخدم اختار مجلداً عبر منتقي النظام: نثبّت الإذن الدائم ثم نتحقق
     * تحققاً حقيقياً من إمكانية الوصول (قراءة وكتابة) قبل إقرار الجدولة —
     * التغذية الراجعة المطلوبة: «تم تحديد مجلد نسخ الاحتياطية» أو
     * «لم يتم تحديد مجلد نسخ الاحتياطية».
     */
    fun onBackupFolderSelected(uri: Uri) {
        viewModelScope.launch {
            // إذن دائم يبقى بعد إعادة تشغيل الجهاز — بدونه يموت الوصول لاحقاً
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }

            val accessible = autoBackupExporter.verifyFolderAccess(uri)
            if (accessible) {
                autoBackupPreferences.setTreeUri(uri)
                val settings = autoBackupPreferences.snapshot()
                autoBackupScheduler.applyPeriodic(settings.intervalDays)
                messenger.notify("تم تحديد مجلد نسخ الاحتياطية ✓")
            } else {
                autoBackupPreferences.setTreeUri(null)
                autoBackupScheduler.cancelPeriodic()
                messenger.notifyError("لم يتم تحديد مجلد نسخ الاحتياطية — تعذّر الوصول للمجلد المختار")
            }
        }
    }

    /** إلغاء المجلد المحدد وإيقاف الجدولة الدورية */
    fun clearBackupFolder() {
        viewModelScope.launch {
            autoBackupPreferences.setTreeUri(null)
            autoBackupScheduler.cancelPeriodic()
            messenger.notify("أُلغي مجلد النسخ الاحتياطية وتوقف النسخ التلقائي")
        }
    }

    /** اختيار الفاصل: يومي/3 أيام/أسبوعي/شهري — يعيد جدولة العامل فوراً */
    fun setIntervalDays(days: Int) {
        viewModelScope.launch {
            autoBackupPreferences.setIntervalDays(days)
            val settings = autoBackupPreferences.snapshot()
            if (settings.isFolderConfigured) {
                autoBackupScheduler.applyPeriodic(days)
            }
        }
    }

    /** خانة اليوم المخصص: تُطبق فوراً إن كانت قيمة صالحة (1..365) */
    fun setCustomDaysInput(raw: String) {
        viewModelScope.launch {
            autoBackupPreferences.setCustomDaysInput(raw)
            val days = raw.toIntOrNull()
            if (days != null && days in AutoBackupPreferences.MIN_INTERVAL_DAYS..AutoBackupPreferences.MAX_INTERVAL_DAYS) {
                autoBackupPreferences.setIntervalDays(days)
                val settings = autoBackupPreferences.snapshot()
                if (settings.isFolderConfigured) {
                    autoBackupScheduler.applyPeriodic(days)
                }
            }
        }
    }

    /** خيار «نسخة بعد كل عملية تعديل» — وعند تفعيله نجرب فوراً للتأكد من عمله */
    fun setBackupOnChange(enabled: Boolean) {
        viewModelScope.launch {
            autoBackupPreferences.setBackupOnChange(enabled)
            if (enabled) {
                val settings = autoBackupPreferences.snapshot()
                if (settings.isFolderConfigured) {
                    autoBackupScheduler.enqueueOnceLatest()
                    messenger.notify("فُعّل النسخ بعد كل تعديل — تجربة نسخة الآن للتحقق")
                } else {
                    messenger.notifyError("حدد مجلد النسخ الاحتياطية أولاً")
                }
            }
        }
    }

    /** نسخ فوري يدوي إلى المجلد المحدد نفسه */
    fun backupNow() {
        viewModelScope.launch {
            val settings = autoBackupPreferences.snapshot()
            if (!settings.isFolderConfigured) {
                messenger.notifyError("لم يتم تحديد مجلد نسخ الاحتياطية")
                return@launch
            }
            autoBackupScheduler.enqueueOnceLatest()
            messenger.notify("طُلبت نسخة احتياطية الآن — راقب النتيجة هنا بعد لحظات")
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            runCatching { dataMaintenanceRepository.clearAllData() }
                .onSuccess { messenger.notify("مُسحت جميع البيانات بنجاح") }
                .onFailure { messenger.notifyError("فشل مسح البيانات") }
        }
    }
}
