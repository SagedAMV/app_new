package com.unihub.app.feature.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unihub.app.data.cloud.CloudUploadPlan
import com.unihub.app.data.cloud.CloudUploadReport
import com.unihub.app.data.cloud.CloudTransferState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudUploadConfirmationSheet(
    plan: CloudUploadPlan, isOnline: Boolean, transfer: CloudTransferState, report: CloudUploadReport?,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onCancel: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(plan.title, style = MaterialTheme.typography.titleLarge)
            Text(plan.summary, style = MaterialTheme.typography.titleMedium)
            Text("سيُرفع المحدد فقط مع حفظ أسماء المجلدات وتداخلها. الملفات الأخرى في هاتفك لن تُرفع.",
                style = MaterialTheme.typography.bodyMedium)
            if (plan.missingFiles.isNotEmpty()) Text("غير متاح محلياً: ${plan.missingFiles.take(4).joinToString("، ")}",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!isOnline) Text("اتصل بالإنترنت لبدء الرفع", color = MaterialTheme.colorScheme.error)
            CloudTransferProgressCard(transfer, onCancel)
            report?.let { Text(it.message, color = if (it.failedNames.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("إغلاق") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onConfirm, enabled = isOnline && !transfer.active && (plan.fileIds.isNotEmpty() || plan.folderIds.isNotEmpty())) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text("رفع المحدد إلى السحابة")
                }
            }
        }
    }
}
