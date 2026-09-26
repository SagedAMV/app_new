package com.unihub.app.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.unihub.app.MainActivity
import com.unihub.app.core.prefs.ThemeMode
import com.unihub.app.data.backup.BackupRepository
import com.unihub.app.data.backup.BackupSignature
import com.unihub.app.ui.theme.UniHubTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * نقطة دخول المشاركة من تطبيقات النظام (واتساب، الملفات، الاستوديو...).
 *
 * المساران:
 * 1) ملف يحمل شفرة نسخة احتياطية ([BackupSignature]) → حوار «هل تريد استيراد
 *    نسخة؟» مع زر رفض — بدل مسار المشاركة العادي تماماً كما طلبت التعليمات.
 * 2) ملفات عادية → تُنسخ فوراً إلى «صندوق المشاركة» ([ShareInbox]) لأن أذونات
 *    المحتوى مؤقتة، ثم يُفتح التطبيق ليختار المستخدم المجلد الوجهة.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var shareInbox: ShareInbox
    @Inject lateinit var backupRepository: BackupRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = collectSharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        val backupFile = uris.firstOrNull { isBackupFile(it) }
        if (backupFile != null) {
            showBackupImportDialog(backupFile)
        } else {
            ingestAndOpenApp(uris)
        }
    }

    /** هل يبدأ الملف بشفرة النسخ الاحتياطي؟ قراءة أول بايتات فقط — بلا تحميل كامل */
    private fun isBackupFile(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(128)
            val read = stream.read(head)
            read > 0 && BackupSignature.startsWithSignature(head.copyOf(read))
        } ?: false
    }.getOrDefault(false)

    /** المسار العادي: نسخ فوري إلى الصندوق ثم فتح التطبيق الرئيسي */
    private fun ingestAndOpenApp(uris: List<Uri>) {
        // النسخ قبل أي تنقل — إذن القراءة على ملفات المشاركة يموت مع هذا النشاط
        lifecycleScope.launch {
            try {
                shareInbox.ingest(uris)
                startActivity(
                    Intent(this@ShareReceiverActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            } finally {
                finish()
            }
        }
    }

    /** مسار الشفرة: حوار استيراد نسخة مع زر رفض صريح */
    private fun showBackupImportDialog(uri: Uri) {
        setContent {
            UniHubTheme(mode = ThemeMode.SYSTEM, useDynamicColor = false) {
                var phase by remember {
                    mutableStateOf<ImportPhase>(ImportPhase.Asking)
                }
                val scope = rememberCoroutineScope()

                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("هل تريد استيراد نسخة؟") },
                    text = {
                        when (val current = phase) {
                            ImportPhase.Asking -> Text(
                                "هذا الملف نسخة احتياطية من UniHub. استيرادها يستبدل كل " +
                                    "البيانات الحالية بمحتوى النسخة."
                            )
                            ImportPhase.Importing -> Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp), strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("جارٍ استيراد النسخة…")
                            }
                            is ImportPhase.Done -> Text(
                                current.message,
                                color = if (current.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    confirmButton = {
                        when (phase) {
                            ImportPhase.Asking -> TextButton(onClick = {
                                phase = ImportPhase.Importing
                                scope.launch {
                                    phase = backupRepository.import(uri).fold(
                                        onSuccess = { ImportPhase.Done(true, "تم استيراد $it عنصراً من النسخة") },
                                        onFailure = { ImportPhase.Done(false, "فشل الاستيراد: ${it.message}") }
                                    )
                                }
                            }) { Text("استيراد") }
                            is ImportPhase.Done -> TextButton(onClick = { finish() }) {
                                Text("إغلاق")
                            }
                            ImportPhase.Importing -> Unit
                        }
                    },
                    dismissButton = {
                        if (phase == ImportPhase.Asking) {
                            TextButton(onClick = { finish() }) { Text("رفض") }
                        }
                    }
                )
            }
        }
    }

    /** حالة حوار الاستيراد */
    private sealed interface ImportPhase {
        data object Asking : ImportPhase
        data object Importing : ImportPhase
        data class Done(val success: Boolean, val message: String) : ImportPhase
    }

    /** جمع روابط المشاركة من نية الإرسال المفرد أو المتعدد مع بديل clipData */
    private fun collectSharedUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(uris::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(uris::addAll)
            }
        }
        // بديل دفاعي: بعض التطبيقات تضع الروابط في clipData دون EXTRA_STREAM
        if (uris.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let(uris::add)
                }
            }
        }
        return uris.distinct()
    }
}
