package com.unihub.app.data.cloud

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.unihub.app.core.common.Formatters
import com.unihub.app.notifications.CloudTransferNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * محرك النقل في الخلفية: يسحب دفعات [CloudTransferQueueStore] واحدًا تلو الآخر عبر
 * [CloudSyncManager] (نفس منطق الشبكة والمفتاح والحفظ، فلا حقيقة مزدوجة)، ويُبقي إشعارًا
 * مستمرًا فيه التقدّم وزرّا «إيقاف مؤقت» و«إلغاء».
 *
 * لماذا يستمر بعد إغلاق التطبيق؟ لأنه عامل [CoroutineWorker] في نظام WorkManager لا
 * في نطاق ViewModel: يقتصر دور الشاشة على إدخال الطابور فقط. ومتى انقطع الإنترنت تبقى
 * الدفعة في الانتظار ويعيدها النظام عند توفر الاتصال (شرط الشبكة + Backoff في
 * [CloudSyncScheduler]) — بلا إعادة ما اكتمل، لأن [CloudSyncManager] يتخطى الموجود فعلًا.
 */
@HiltWorker
class CloudTransferWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: CloudSyncManager,
    private val queue: CloudTransferQueueStore,
    private val scheduler: CloudSyncScheduler
) : CoroutineWorker(appContext, params) {

    private sealed interface BatchOutcome {
        data class Done(val note: String) : BatchOutcome
        /** انقطاع الشبكة: يبقى في الانتظار ولا يستهلك محاولة، والاستئناف بتسليح مقيّد بالشبكة */
        data class Defer(val message: String) : BatchOutcome
        /** قفل المدير مشغول بعملية أخرى: نحن على شبكة سليمة، فجولة بعد backoff هي الأنسب */
        data class Busy(val message: String) : BatchOutcome
        /** عطل سيُعاد بعد استنفاد المحاولات فيُدرج ضمن الاستثناءات */
        data class Failed(val message: String) : BatchOutcome
    }

    override suspend fun doWork(): Result {
        var busy = false
        var completed = 0
        while (true) {
            if (isStopped) break
            val snapshot = queue.snapshot()
            val batch = CloudTransferQueueLogic.nextPending(snapshot) ?: break
            if (!manager.checkIsOnline()) break // القرار النهائي يقيس الشبكة مجددًا عند الخروج
            publishProgress(snapshot, batch)
            when (val outcome = runCatching { runBatch(batch) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error else classify(error)
                }) {
                is BatchOutcome.Done -> {
                    completed += batch.itemCount
                    queue.update { CloudTransferQueueLogic.markDone(it, batch.id, outcome.note) }
                }
                is BatchOutcome.Defer, is BatchOutcome.Busy -> {
                    val message = when (outcome) { is BatchOutcome.Defer -> outcome.message; else -> (outcome as BatchOutcome.Busy).message }
                    // السبب يُحفظ مع العنصر المعلّق حتى تشرح الواجهة للمستخدم لماذا لا يتحرك
                    queue.update { CloudTransferQueueLogic.markFailed(it, batch.id, message, retryLater = true) }
                    busy = outcome is BatchOutcome.Busy
                }
                is BatchOutcome.Failed -> queue.update { CloudTransferQueueLogic.markFailed(it, batch.id, outcome.message, retryLater = false) }
            }
        }
        val snapshot = queue.snapshot()
        // عند الإيقاف المؤقت يبقى إشعار «استئناف» ظاهرًا وإلا يُمحى — وإلا صار الزر بلا أثر
        if (snapshot.paused) CloudTransferNotifier.show(appContext, CloudTransferNotifier.paused(appContext, snapshot))
        else CloudTransferNotifier.cancel(appContext)
        // إشعار نتيجة على قناة عالية الأهمية: هو ما ينتبه له المستخدم وهو في تطبيق آخر.
        // مشروط بعمل هذا التشغيل حتى لا يُعاد التنبيه لنفس الاستثناءات القديمة كل جولة.
        if (completed > 0 || snapshot.failed.isNotEmpty()) CloudTransferNotifier.showResult(appContext, snapshot, completed)
        // النتيجة المردودة من عاملٍ أوقفه النظام لسقوط شرط الشبكة **تُهمَل**، فالتسليح
        // صراحةً هو وحده الذي يضمن الاستئناف عند عودة الاتصال (تقرير عطل 2026-10-08).
        return when (CloudTransferQueueLogic.rearmAction(snapshot, manager.checkIsOnline(), isStopped, busy)) {
            CloudTransferRearm.WAIT_FOR_NETWORK -> {
                scheduler.enqueueTransfers()
                Result.success()
            }
            CloudTransferRearm.RETRY_SOON -> Result.retry()
            CloudTransferRearm.NONE -> Result.success()
        }
    }

    private suspend fun runBatch(batch: CloudTransferBatch): BatchOutcome = when (batch.kind) {
        CloudTransferKind.UPLOAD -> runUpload(batch)
        CloudTransferKind.DOWNLOAD -> runDownload(batch)
        CloudTransferKind.NONE -> BatchOutcome.Failed("دفعة نقل بلا نوع؛ أعد الطلب من الشاشة")
    }

    private suspend fun runUpload(batch: CloudTransferBatch): BatchOutcome {
        // الخطة تُعاد بنيتها الآن: لو حُذف ملف محلي أثناء الانتظار تظهر في missingFiles
        // بدل أن يفشل الرفع كله، وبنية المجلدات تُحسب كما حسبها زر «رفع المحدد».
        val plan = manager.prepareUploadPlan(batch.fileIds, batch.folderIds, batch.title)
        if (plan.fileIds.isEmpty()) {
            return if (plan.missingFiles.isEmpty()) BatchOutcome.Failed("لا توجد ملفات قابلة للرفع في هذا الطلب")
            else BatchOutcome.Failed("لم تعد هذه الملفات موجودة على الجهاز")
        }
        val missingNote = if (plan.missingFiles.isEmpty()) "" else " • لم يوجد ${plan.missingFiles.size} ملف على الجهاز"
        return manager.uploadSelected(plan).fold(
            onSuccess = { report ->
                if (report.failedNames.isEmpty()) {
                    val skipped = if (report.alreadyPresentCount > 0) " (وتجاوز ${report.alreadyPresentCount} موجودًا مسبقًا)" else ""
                    BatchOutcome.Done("رُفع ${Formatters.fileCountLabel(report.uploadedCount)}$skipped$missingNote")
                } else {
                    BatchOutcome.Failed("تعذّر رفع ${report.failedNames.size}: ${report.failedNames.take(3).joinToString("، ")}$missingNote")
                }
            },
            onFailure = { classify(it) }
        )
    }

    private suspend fun runDownload(batch: CloudTransferBatch): BatchOutcome {
        val wanted = batch.remoteKeys.toSet()
        val files = manager.allRemoteFiles.value.filter { it.remoteKey in wanted }
        if (files.isEmpty() && batch.remoteFolderKeys.isEmpty()) {
            return BatchOutcome.Failed("لم تعد هذه الملفات في قائمة السحابة؛ أعد الفحص ثم حاول مجددًا")
        }
        val goneNote = if (files.size < wanted.size) " • لم يُعثر على ${wanted.size - files.size} منها" else ""
        return manager.downloadSelectedFiles(files, batch.remoteFolderKeys, batch.destination).fold(
            onSuccess = { report ->
                if (report.failedNames.isEmpty()) {
                    val foldersNote = if (report.createdFolders > 0) " داخل ${report.createdFolders} مجلد" else ""
                    BatchOutcome.Done("نُزّل ${Formatters.fileCountLabel(report.downloadedCount)}$foldersNote$goneNote")
                } else {
                    BatchOutcome.Failed("تعذّر تنزيل ${report.failedNames.size}: ${report.failedNames.take(3).joinToString("، ")}$goneNote")
                }
            },
            onFailure = { classify(it) }
        )
    }

    /**
     * تصنيف العطل: الانشغال وانقطاع الاتصال «تأجيل» لا فشل — لئلا تُستهلك المحاولات الأربع
     * أثناء انقطاع طويل فيحتاج المستخدم تدخلاً يدويًا بلا سبب.
     */
    private fun classify(error: Throwable): BatchOutcome {
        // ما يُرجَع هنا يُعرض حرفيًا في إشعار الطابور وبطاقته، فيُمنع اسم الصنف ورمز الحالة
        // (طبيعة تطبيق.md §5). السابق كان error.message ?: «عربي» فجعل العربية احتياطًا فقط،
        // فعند انقطاع الشبكة وصلت «UnknownHostException: Unable to resolve host…» إلى الواجهة.
        val reason = CloudFailureMessages.userMessage(error, networkAvailable = if (manager.checkIsOnline()) null else false)
        return when {
            // إلغاء بإرادة المستخدم (زر الإيقاف) أو بإنهاء النظام للعامل: نُبقي الجزء والنطاق كما هما
            error is CancellationException -> BatchOutcome.Defer(reason)
            error is CloudBusyException -> BatchOutcome.Busy(error.message?.takeIf { CloudFailureMessages.isUserFacing(it) } ?: "توجد عملية نقل جارية")
            !manager.checkIsOnline() -> BatchOutcome.Defer(reason)
            else -> BatchOutcome.Failed(reason)
        }
    }

    private suspend fun publishProgress(snapshot: CloudTransferQueueSnapshot, batch: CloudTransferBatch) {
        val notification = CloudTransferNotifier.progress(appContext, snapshot, batch)
        // الترتيب هنا هو العطل المُصلَّح: كان الإشعار مشروطًا بفشل setForegroundAsync، وذلك
        // لا يستثني عند الرفض بل يردّ false — فلا إشعار إطلاقًا خارج التطبيق (الخدمة الأمامية
        // مرفوضة من الخلفية، و«نجح» الردّ). الإشعار يُنشر أولًا بلا شرط، والخدمة الأمامية
        // تُطلب بعده للإبقاء فقط، وفشلها يُسجَّل ولا يُلغي النقل ولا إخفاءه.
        CloudTransferNotifier.show(appContext, notification)
        runCatching { setForeground(ForegroundInfo(CloudTransferNotifier.NOTIFICATION_ID, notification)) }
            .onFailure { Log.w(TAG, "رفض النظام خدمة أمامية لهذا النقل؛ إشعار الطابور يعمل وحده", it) }
    }

    private companion object {
        private const val TAG = "CloudTransferWorker"
    }

}
