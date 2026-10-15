package com.unihub.app.feature.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.unihub.app.data.cloud.CloudUploadTarget
import com.unihub.app.data.cloud.RemoteCloudFolder
import com.unihub.app.data.cloud.CloudUploadReport
import com.unihub.app.data.cloud.CloudTransferQueueSnapshot
import com.unihub.app.data.cloud.CloudTransferState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudUploadConfirmationSheet(
    plan: CloudUploadPlan, isOnline: Boolean, transfer: CloudTransferState, report: CloudUploadReport?,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
    queue: CloudTransferQueueSnapshot = CloudTransferQueueSnapshot(),
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onDropPending: () -> Unit = {},
    /** اختيارية بقيم افتراضية: أي مستدعٍ قديم للبطاقة يبقى يترجم دون تعديل */
    cloudFolders: List<RemoteCloudFolder> = emptyList(),
    targetFolderKey: String? = null,
    onSelectTarget: (key: String?, name: String?) -> Unit = { _, _ -> }
) {
    val selectableFolders = cloudFolders.filter { CloudUploadTarget.isSelectable(it.key) && it.key != null }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(plan.title, style = MaterialTheme.typography.titleLarge)
            Text(plan.summary, style = MaterialTheme.typography.titleMedium)
            Text(plan.destinationLabel, style = MaterialTheme.typography.bodyMedium)
            if (selectableFolders.isNotEmpty()) {
                Text("وجهة الرفع داخل السحابة", style = MaterialTheme.typography.labelLarge)
                // LazyRow أفقي: قائمة منسدلة داخل ModalBottomSheet تفتح فوق حافة الشاشة وتُقطع
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = targetFolderKey == null,
                            onClick = { onSelectTarget(null, null) },
                            label = { Text(CloudUploadTarget.ROOT_LABEL) }
                        )
                    }
                    items(selectableFolders, key = { it.key }) { folder ->
                        FilterChip(
                            selected = folder.key == targetFolderKey,
                            onClick = { onSelectTarget(folder.key, folder.name) },
                            label = { Text(folder.name) }
                        )
                    }
                }
            }
            if (plan.missingFiles.isNotEmpty()) Text("غير متاح: ${plan.missingFiles.take(4).joinToString("، ")}",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!isOnline) Text("لا إنترنت، سيُرفع عند العودة", style = MaterialTheme.typography.bodySmall)
            CloudTransferProgressCard(
                state = transfer,
                queue = queue,
                onPause = onPause,
                onResume = onResume,
                onRetryFailed = onRetryFailed,
                onDropPending = onDropPending,
                online = isOnline
            )
            report?.let { Text(it.message, color = if (it.failedNames.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("إغلاق") }
                Spacer(Modifier.width(8.dp))
                // لم يعد «نقل جارٍ» يعطّل الزر: الطلبات تُطابَر وتُنفَّذ واحدًا تلو الآخر،
                // فلا سبب أن يبدو الزر ميتًا (وهو بعينه ما شكا منه المستخدم). والإلغاء صريح.
                Button(onClick = onConfirm, enabled = plan.fileIds.isNotEmpty() || plan.folderIds.isNotEmpty()) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp)); Text("رفع")
                }
            }
        }
    }
}
