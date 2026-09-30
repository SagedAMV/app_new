package com.unihub.app.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudDownloadLocation
import com.unihub.app.data.local.entity.FolderEntity

@Composable
fun CloudDownloadDestinationDialog(
    folders: List<FolderEntity>, cloudFolderName: String?,
    onConfirm: (CloudDownloadDestination) -> Unit, onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(CloudDownloadLocation.LOCAL_FOLDER) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var rootName by remember { mutableStateOf(cloudFolderName.orEmpty()) }
    val selected = folders.firstOrNull { it.id == selectedId }
    fun path(folder: FolderEntity): String {
        val map = folders.associateBy { it.id }; val parts = mutableListOf<String>(); val seen = mutableSetOf<Long>()
        var current: FolderEntity? = folder
        while (current != null && seen.add(current.id)) { parts += current.name; current = current.parentId?.let { map[it] } }
        return parts.asReversed().joinToString(" / ")
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("أين تريد حفظ التنزيل؟") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DestinationModeRow("داخل مجلد أختاره — دون أسماء مجلدات السحابة", mode == CloudDownloadLocation.LOCAL_FOLDER) { mode = CloudDownloadLocation.LOCAL_FOLDER }
            if (cloudFolderName != null) DestinationModeRow("المجلد داخل وجهتي مع الاحتفاظ بمجلداته الفرعية", mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL) { mode = CloudDownloadLocation.FOLDER_INSIDE_LOCAL }
            DestinationModeRow("حسب شجرة مجلدات السحابة الأصلية", mode == CloudDownloadLocation.ORIGINAL_CLOUD_TREE) { mode = CloudDownloadLocation.ORIGINAL_CLOUD_TREE }
            if (mode != CloudDownloadLocation.ORIGINAL_CLOUD_TREE) {
                Text("اختر وجهة في هاتفك:", style = MaterialTheme.typography.titleSmall)
                DestinationModeRow("الرئيسية (مكتبة التطبيق)", selectedId == null) { selectedId = null }
                folders.sortedBy(::path).forEach { folder ->
                    DestinationModeRow(path(folder), selectedId == folder.id) { selectedId = folder.id }
                }
                if (mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL) OutlinedTextField(
                    value = rootName, onValueChange = { rootName = it }, label = { Text("اسم المجلد الجديد عندك") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(if (mode == CloudDownloadLocation.LOCAL_FOLDER) "لن ننشئ مجلدات بأسماء السحابة، ولن نعيد تسمية وجهتك."
                    else "لا ننشئ أسلاف المجلد السحابي؛ نحفظ الجزء الذي اخترته فقط داخل وجهتك.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        Button(onClick = { onConfirm(CloudDownloadDestination(mode, selected?.id, selected?.createdAt,
            rootName.takeIf { mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL })) },
            enabled = mode != CloudDownloadLocation.FOLDER_INSIDE_LOCAL || rootName.isNotBlank()) { Text("بدء التنزيل إلى هذه الوجهة") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
private fun DestinationModeRow(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = checked, onClick = onClick)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
