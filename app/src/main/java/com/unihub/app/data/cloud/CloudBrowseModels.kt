package com.unihub.app.data.cloud

import com.unihub.app.core.common.Formatters
import java.io.IOException

/** حالات صريحة: الفراغ نتيجة فحص ناجح، وليس استنتاجاً من قائمة لم تُحمّل. */
enum class CloudScanPhase { IDLE, SCANNING, EMPTY, READY, ERROR, BUSY }
data class CloudScanState(
    val phase: CloudScanPhase = CloudScanPhase.IDLE,
    val checkedAt: Long = 0L,
    val serverFileCount: Int = 0,
    val serverFolderCount: Int = 0,
    val message: String = "اضغط فحص للتحقق من محتويات السحابة"
)

class CloudBusyException : IOException("توجد عملية نقل جارية؛ القائمة المحفوظة متاحة، ويمكن الفحص بعد انتهائها")
class CloudScanTimeoutException : IOException("انتهت مهلة فحص السحابة؛ تحقق من الاتصال ثم اضغط إعادة المحاولة")

enum class CloudTransferKind { NONE, UPLOAD, DOWNLOAD }
data class CloudTransferState(
    val kind: CloudTransferKind = CloudTransferKind.NONE,
    val fileName: String = "",
    val index: Int = 0,
    val totalFiles: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val phase: String = ""
) {
    val active: Boolean get() = kind != CloudTransferKind.NONE
    val progress: Float? get() = if (bytesTotal > 0) (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f) else null
    val message: String get() = if (phase.isNotBlank()) phase else
        "${if (kind == CloudTransferKind.UPLOAD) "رفع" else "تنزيل"} $index/$totalFiles: $fileName"
}

data class RemoteCloudFolder(
    val key: String,
    val name: String,
    val parentKey: String? = null,
    val createdAt: Long = 0L,
    /** توافق النسخ القديمة؛ لا يُستخدم منفرداً لهوية مجلد محلي. */
    val legacyId: Long? = null
)

data class CloudFolderLink(val localId: Long, val localCreatedAt: Long, val remoteKey: String)

data class CloudUploadPlan(
    val title: String,
    val fileIds: Set<Long>,
    val folderIds: Set<Long>,
    val totalBytes: Long,
    val missingFiles: List<String> = emptyList()
) {
    val summary: String get() = "${Formatters.fileCountLabel(fileIds.size)} • ${Formatters.fileSize(totalBytes)}"
}

data class CloudUploadReport(
    val uploadedCount: Int,
    val alreadyPresentCount: Int,
    val folderCount: Int,
    val failedNames: List<String>
) {
    val message: String get() = buildString {
        append("رُفع $uploadedCount ملف")
        if (alreadyPresentCount > 0) append("، و $alreadyPresentCount موجود مسبقاً")
        if (folderCount > 0) append("؛ حُفظت بنية $folderCount مجلد")
        if (failedNames.isNotEmpty()) append("؛ تعذّر ${failedNames.size}: ${failedNames.take(3).joinToString("، ")}")
    }
}
