package com.unihub.app.feature.files.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * قائمة الإضافة المنبثقة (Bottom Sheet) — تحل محل القائمة المنسدلة القديمة فوق الزر العائم.
 * أربعة خيارات: رفع ملف من الجهاز، مجلد جديد، التقاط صورة بالكاميرا، تسجيل صوتي.
 * الخياران الأولان يحافظان على السلوك السابق كما هو تماماً.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMenuSheet(
    onDismiss: () -> Unit,
    onImportFiles: () -> Unit,
    onNewFolder: () -> Unit,
    onCapturePhoto: () -> Unit,
    onRecordAudio: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "أضف إلى هذا المجلد",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
            )
            Text(
                text = "اختر طريقة الإضافة المناسبة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            )

            AddMenuItem(
                icon = Icons.Filled.UploadFile,
                tint = MaterialTheme.colorScheme.primary,
                title = "رفع ملف",
                subtitle = "من تخزين الجهاز عبر منتقي النظام",
                onClick = onImportFiles
            )
            AddMenuItem(
                icon = Icons.Filled.CreateNewFolder,
                tint = MaterialTheme.colorScheme.tertiary,
                title = "مجلد جديد",
                subtitle = "إنشاء مجلد فرعي داخل هذا المجلد",
                onClick = onNewFolder
            )
            AddMenuItem(
                icon = Icons.Filled.CameraAlt,
                tint = MaterialTheme.colorScheme.secondary,
                title = "التقاط صورة",
                subtitle = "بالكاميرا مباشرة — عدة صور في جلسة واحدة",
                onClick = onCapturePhoto
            )
            AddMenuItem(
                icon = Icons.Filled.Mic,
                tint = MaterialTheme.colorScheme.error,
                title = "تسجيل صوتي",
                subtitle = "سجّل محاضرة أو ملاحظة بصوتك",
                onClick = onRecordAudio
            )
        }
    }
}

/** صف خيار واحد داخل قائمة الإضافة: أيقونة ملوّنة + عنوان + وصف قصير */
@Composable
private fun AddMenuItem(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(46.dp)
                .background(tint.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
