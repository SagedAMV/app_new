package com.unihub.app.feature.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unihub.app.core.prefs.AutoBackupPreferences
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.ui.components.ChoiceChips
import com.unihub.app.ui.components.ConfirmDialog
import com.unihub.app.ui.components.Field
import com.unihub.app.ui.components.SectionHeader
import com.unihub.app.ui.components.UiMessagesHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UiMessagesHost(viewModel.messenger, snackbarHostState)

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()

    var showClearConfirm by remember { mutableStateOf(false) }

    // النسخ الاحتياطي التلقائي: الإعدادات الحية + منتقي مجلد النظام (SAF)
    val autoBackup by viewModel.autoBackupSettings.collectAsStateWithLifecycle()
    val backupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::onBackupFolderSelected) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            SectionHeader(title = "المظهر")

            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = themeMode == mode, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(mode.label(), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        viewModel.setDynamicColor(!useDynamicColor)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("ألوان Material You الديناميكية", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "استخدم ألواناً مشتقة من خلفية جهازك (أندرويد 12+)"
                        } else {
                            "متوفرة على أندرويد 12 فما فوق فقط"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                )
            }

            SectionHeader(title = "النسخ الاحتياطي التلقائي")

            // ── مجلد النسخ: اختيار عبر النظام + تغذية راجعة حقيقية عن الوصول ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("مجلد النسخ التلقائي", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (autoBackup?.isFolderConfigured == true) {
                            "تم تحديد مجلد نسخ الاحتياطية ✓"
                        } else {
                            "لم يتم تحديد مجلد نسخ الاحتياطية"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (autoBackup?.isFolderConfigured == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (autoBackup?.isFolderConfigured == true) {
                    TextButton(onClick = viewModel::clearBackupFolder) { Text("إلغاء") }
                }
                FilledTonalButton(onClick = { backupFolderLauncher.launch(null) }) {
                    Text(if (autoBackup?.isFolderConfigured == true) "تغيير" else "اختيار مجلد")
                }
            }

            // ── فاصل النسخ: يومي / 3 أيام / أسبوعي / شهري / يوم مخصص ──
            val intervalDays = autoBackup?.intervalDays
                ?: AutoBackupPreferences.DEFAULT_INTERVAL_DAYS
            val chipIndex = when (intervalDays) {
                1 -> 0
                3 -> 1
                7 -> 2
                30 -> 3
                else -> 4
            }
            Text(
                "كل كم يُعمل النسخ التلقائي؟",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            ChoiceChips(
                labels = listOf("يومي", "كل 3 أيام", "أسبوعي", "شهري", "مخصص"),
                selectedIndex = chipIndex,
                onSelect = { index ->
                    when (index) {
                        0 -> viewModel.setIntervalDays(1)
                        1 -> viewModel.setIntervalDays(3)
                        2 -> viewModel.setIntervalDays(7)
                        3 -> viewModel.setIntervalDays(30)
                        else -> {
                            // «مخصص»: يطبق القيمة الحالية في خانة اليوم المخصص
                            val custom = autoBackup?.customDaysInput?.toIntOrNull()
                            if (custom != null && custom in AutoBackupPreferences.MIN_INTERVAL_DAYS..AutoBackupPreferences.MAX_INTERVAL_DAYS) {
                                viewModel.setIntervalDays(custom)
                            } else {
                                viewModel.messenger.notifyError("اكتب عدد الأيام في الخانة أولاً (1-365)")
                            }
                        }
                    }
                }
            )

            // خانة اليوم المخصص — تظهر عند اختيار «مخصص» أو وجود قيمة مخصصة
            if (chipIndex == 4) {
                Spacer(Modifier.height(8.dp))
                Field(
                    label = "عدد الأيام المخصص (1-365)",
                    value = autoBackup?.customDaysInput.orEmpty(),
                    onValueChange = viewModel::setCustomDaysInput,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // ── نسخة بعد كل عملية تعديل (تجربة أن النسخ يعمل) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("نسخة بعد كل عملية تعديل", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "للتجربة: تُحدَّث نسخة «آخر حفظ» بعد أي تعديل على بياناتك",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoBackup?.backupOnChange ?: false,
                    onCheckedChange = viewModel::setBackupOnChange
                )
            }

            // ── نسخ الآن + آخر نتيجة ──
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = viewModel::backupNow,
                enabled = autoBackup?.isFolderConfigured == true
            ) {
                Text("نسخ احتياطي الآن")
            }
            val lastAt = autoBackup?.lastBackupAt ?: 0L
            if (lastAt > 0L) {
                val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
                Text(
                    text = "آخر محاولة: ${dateFormat.format(Date(lastAt))}" +
                        autoBackup?.lastBackupMessage?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            SectionHeader(title = "البيانات")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenBackup)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Backup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("النسخ الاحتياطي والاستعادة", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // إصلاح (جلسة التدقيق): النسخة أصبحت أرشيف ZIP يتضمن محتوى
                        // الملفات الفعلي — الوصف القديم «ملف JSON» لم يعد دقيقاً.
                        "تصدير بياناتك إلى أرشيف نسخة احتياطية أو استعادتها",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearConfirm = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "مسح جميع البيانات",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "يحذف كل المجلدات والملفات والمهام والملاحظات نهائياً",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionHeader(title = "حول التطبيق")
            Text(
                text = "UniHub — رفيقك الجامعي لإدارة الملفات والمحاضرات والمهام والملاحظات والامتحانات. " +
                    "يعمل بالكامل دون إنترنت وتبقى بياناتك على جهازك.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "الإصدار 1.0.0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showClearConfirm) {
            ConfirmDialog(
                title = "مسح جميع البيانات؟",
                message = "سيُحذف كل شيء نهائياً: المجلدات، الملفات المستوردة، المهام، الملاحظات، الامتحانات والجدول. لا يمكن التراجع.",
                confirmLabel = "مسح الكل",
                onConfirm = {
                    viewModel.clearAllData()
                    showClearConfirm = false
                },
                onDismiss = { showClearConfirm = false }
            )
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "تلقائي (حسب النظام)"
    ThemeMode.LIGHT -> "فاتح"
    ThemeMode.DARK -> "داكن"
}
