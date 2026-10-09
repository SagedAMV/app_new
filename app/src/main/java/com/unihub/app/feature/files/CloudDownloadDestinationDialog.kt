package com.unihub.app.feature.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unihub.app.data.cloud.CloudDownloadDefaults
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudDownloadLocation
import com.unihub.app.data.local.entity.FolderEntity

/**
 * حوار وجهة التنزيل — جولة تعليمات.md:
 * 1) تسميات واضحة: «سحب ملفات فقط» / «سحب مجلد كامل» / «ترتيب تلقائي — موصى به»،
 *    والأخير هو المفعّل افتراضياً عند فتح الحوار (مصدر وحيد CloudDownloadDefaults)
 *    مع بقاء الاختيار كاملاً للمستخدم.
 * 2) منتقي وجهة في الهاتف بشجرة قابلة للتوسيع: النقر على مجلد يوسّعه ويظهر ما
 *    بداخله تحته (السلوك المألوف في تطبيقات الملفات) بدل القائمة المسطحة
 *    «فيزياء / ملخصات» التي كانت تعرض كل مسار في سطر منفصل.
 */
@Composable
fun CloudDownloadDestinationDialog(
    folders: List<FolderEntity>, cloudFolderName: String?,
    onConfirm: (CloudDownloadDestination) -> Unit, onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(CloudDownloadDefaults.location) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var rootName by remember { mutableStateOf(cloudFolderName.orEmpty()) }
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val selected = folders.firstOrNull { it.id == selectedId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("أين تريد حفظ التنزيل؟") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DestinationModeRow("سحب ملفات فقط", mode == CloudDownloadLocation.LOCAL_FOLDER) { mode = CloudDownloadLocation.LOCAL_FOLDER }
            if (cloudFolderName != null) DestinationModeRow("سحب مجلد كامل", mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL) { mode = CloudDownloadLocation.FOLDER_INSIDE_LOCAL }
            DestinationModeRow("ترتيب تلقائي — موصى به", mode == CloudDownloadLocation.ORIGINAL_CLOUD_TREE) { mode = CloudDownloadLocation.ORIGINAL_CLOUD_TREE }
            if (mode != CloudDownloadLocation.ORIGINAL_CLOUD_TREE) {
                Text("اختر وجهة في هاتفك:", style = MaterialTheme.typography.titleSmall)
                DestinationModeRow("الرئيسية (مكتبة التطبيق)", selectedId == null) { selectedId = null }
                DestinationFolderTree.rows(folders, expandedIds, selectedId).forEach { row ->
                    DestinationFolderTreeRow(
                        row = row,
                        onExpandToggle = {
                            expandedIds = if (row.expanded) expandedIds - row.folder.id else expandedIds + row.folder.id
                        },
                        onSelect = { selectedId = row.folder.id }
                    )
                }
                if (mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL) OutlinedTextField(
                    value = rootName, onValueChange = { rootName = it }, label = { Text("اسم المجلد") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                // جولة المحاكمة (هجوم سيناريو S8): زر معطّل بلا تفسير يربك المستخدم —
                // رسالة صريحة عندما يكون الاسم فارغاً
                if (mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL && rootName.isBlank()) Text(
                    "أدخل اسماً للمجلد",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(if (mode == CloudDownloadLocation.LOCAL_FOLDER) "لن تتغير المجلدات أو الاسم."
                    else "سيُحفظ المحدد فقط.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        Button(onClick = { onConfirm(CloudDownloadDestination(mode, selected?.id, selected?.createdAt,
            rootName.takeIf { mode == CloudDownloadLocation.FOLDER_INSIDE_LOCAL })) },
            enabled = mode != CloudDownloadLocation.FOLDER_INSIDE_LOCAL || rootName.isNotBlank()) { Text("تنزيل") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

/** صف خيار الوضع (RadioButton + نص). */
@Composable
private fun DestinationModeRow(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = checked, onClick = onClick)
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * صف مجلد في شجرة الوجهة: النقر على الصف يختار المجلد وجهةً ويوسّعه ليظهر ما
 * بداخله، والسهم يطوي/يعيد التوسيع دون تغيير الاختيار — فصلٌ مقصود حتى لا يُجبر
 * من يريد التصفح فقط على تغيير وجهته. الإزاحة حسب العمق تُبقي التراتبية واضحة.
 */
@Composable
private fun DestinationFolderTreeRow(
    row: DestinationFolderRow,
    onExpandToggle: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                onSelect()
                if (row.hasChildren && !row.expanded) onExpandToggle()
            }
            .padding(start = (row.depth * 18).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.hasChildren) {
            IconButton(onClick = onExpandToggle) {
                Icon(
                    imageVector = if (row.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (row.expanded) "طي ${row.folder.name}" else "توسيع ${row.folder.name}"
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        RadioButton(selected = row.isSelected, onClick = onSelect)
        Text(row.folder.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
