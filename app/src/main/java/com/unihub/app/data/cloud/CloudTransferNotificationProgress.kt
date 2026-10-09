package com.unihub.app.data.cloud

import com.unihub.app.core.common.Formatters
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * عرض تقدّم نقل واحد في إشعار Android.
 *
 * الشريط يتقدّم عبر ملفات العملية: الجزء المكتمل يمثّل الملفات السابقة، والجزء الحالي
 * يُحسب من البايتات المنقولة فعليًا. يبقى الحد الأقصى 99% حتى يؤكد مدير النقل نجاح
 * الملف/الدفعة؛ وصول آخر بايت لا يعني وحده أن التحقق والحفظ قد اكتملَا.
 * هذه دوال نقية لتكون قابلة للاختبار على JVM.
 */
internal data class CloudTransferNotificationProgress(
    val percentage: Int,
    val detail: String,
    val currentFilePercentage: Int?
)

internal object CloudTransferNotificationProgressFactory {
    fun from(state: CloudTransferState?): CloudTransferNotificationProgress {
        if (state == null || state.kind == CloudTransferKind.NONE) {
            return CloudTransferNotificationProgress(
                percentage = 0,
                detail = "جارٍ تجهيز عملية النقل…",
                currentFilePercentage = null
            )
        }

        val totalFiles = state.totalFiles.coerceAtLeast(1)
        val index = state.index.coerceIn(1, totalFiles)
        val fileFraction = if (state.bytesTotal > 0L) {
            (state.bytesDone.toDouble() / state.bytesTotal.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val wholeFraction = if (state.batchBytesTotal > 0L) {
            val aggregateDone = (state.bytesCompletedBeforeCurrentFile.coerceAtLeast(0L) +
                state.bytesDone.coerceAtLeast(0L)).coerceIn(0L, state.batchBytesTotal)
            aggregateDone.toDouble() / state.batchBytesTotal.toDouble()
        } else {
            // احتياط للمراحل القديمة التي لا تعرف الحجم الإجمالي للدفعة.
            ((index - 1).toDouble() + fileFraction) / totalFiles.toDouble()
        }
        // لا نعرض 100% قبل عودة العملية بنجاح وتثبيت نتيجتها في الطابور.
        val percentage = floor(wholeFraction * 100.0).toInt().coerceIn(0, 99)
        val filePercentage = state.progress?.let { (it * 100f).roundToInt().coerceIn(0, 100) }

        val detail = when {
            state.phase.isNotBlank() -> state.phase
            state.bytesTotal > 0L -> "${state.fileName}: ${Formatters.fileSize(state.bytesDone.coerceIn(0L, state.bytesTotal))} / ${Formatters.fileSize(state.bytesTotal)}"
            state.fileName.isNotBlank() -> state.fileName
            else -> state.message
        }

        return CloudTransferNotificationProgress(
            percentage = percentage,
            detail = detail,
            currentFilePercentage = filePercentage
        )
    }
}
