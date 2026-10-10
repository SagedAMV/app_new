package com.unihub.app.data.cloud

import kotlin.math.floor

/** النسبة الوحيدة اللازمة لإشعار نقل الملفات؛ بقية تفاصيل الحالة تبقى داخل التطبيق. */
internal data class CloudTransferNotificationProgress(
    val percentage: Int
)

internal object CloudTransferNotificationProgressFactory {
    fun from(state: CloudTransferState?): CloudTransferNotificationProgress {
        if (state == null || state.kind == CloudTransferKind.NONE) {
            return CloudTransferNotificationProgress(percentage = 0)
        }

        val totalFiles = state.totalFiles.coerceAtLeast(1)
        val index = state.index.coerceIn(1, totalFiles)
        val fileFraction = if (state.bytesTotal > 0L) {
            (state.bytesDone.toDouble() / state.bytesTotal.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val wholeFraction = if (state.batchBytesTotal > 0L) {
            val total = state.batchBytesTotal
            val completedBefore = state.bytesCompletedBeforeCurrentFile.coerceIn(0L, total)
            // جمع آمن لا يلتف إلى قيمة سالبة إذا جاءت حالة معطوبة بأحجام ضخمة.
            val remaining = total - completedBefore
            val currentDone = state.bytesDone.coerceAtLeast(0L).coerceAtMost(remaining)
            (completedBefore + currentDone).toDouble() / total.toDouble()
        } else {
            // توافق المراحل القديمة التي لا توفر مجموع أحجام الدفعة.
            ((index - 1).toDouble() + fileFraction) / totalFiles.toDouble()
        }

        // لا نعرض 100% حتى يؤكد مدير النقل نجاح الملف وتثبيت نتيجته في الطابور.
        val percentage = floor(wholeFraction.coerceIn(0.0, 1.0) * 100.0).toInt().coerceIn(0, 99)
        return CloudTransferNotificationProgress(percentage = percentage)
    }
}
