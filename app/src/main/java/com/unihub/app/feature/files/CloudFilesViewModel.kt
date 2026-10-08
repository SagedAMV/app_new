package com.unihub.app.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.Formatters
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.CloudSyncScheduler
import com.unihub.app.data.cloud.CloudTransferBatch
import com.unihub.app.data.cloud.CloudTransferKind
import com.unihub.app.notifications.CloudTransferControls
import com.unihub.app.data.cloud.CloudTransferQueueSnapshot
import com.unihub.app.data.cloud.CloudTransferQueueStore
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.RemoteCloudFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * حالة شاشة السحابة المستقلة [CloudFilesScreen]: يغلّف [CloudSyncManager] المشترك
 * (Singleton واحد لكل التطبيق) ولا يحتفظ بأي حالة خاصة به — فالشاشة تقرأ منه
 * مباشرةً بمصدر حقيقة واحد، وأي شاشة أخرى تفتحها ترى الحالة ذاتها بلا مزامنة.
 *
 * جولة تعليمات.md: أُضيفت عمليات التحكم داخل السحابة نفسها (إعادة تسمية ملف،
 * تغيير مسار ملفات، إعادة تسمية مجلد) كدوال رفيعة فوق عمليات المدير، مع قناة
 * رسائل [UiMessenger] لتأكيد النجاح أو شرح سبب الفشل مباشرة في الشاشة.
 * وأُزيلت بوابة الإشعار التي كانت تفتح السحابة عند نقره لأن فتح السحابة صار
 * محصوراً في زرّي الشريط العلوي (الرئيسية والملفات) بقرار توحيد نقاط الدخول.
 */
@HiltViewModel
class CloudFilesViewModel @Inject constructor(
    private val manager: CloudSyncManager,
    private val authManager: CloudAuthManager,
    private val queue: CloudTransferQueueStore,
    private val scheduler: CloudSyncScheduler,
    private val controls: CloudTransferControls
) : ViewModel() {

    val messenger = UiMessenger()
    val authSession = authManager.sessionState

    val files = manager.allRemoteFiles
    val localFolders = manager.localFolders
    val verifying = manager.verifyingKeys
    val verification = manager.localVerification
    val available = manager.availableRemoteFiles
    val folders = manager.remoteFolders
    val scanState = manager.scanState
    val transfer = manager.transferState
    val online = manager.isOnline
    val report = manager.lastDownloadReport
    /** حالة طابور الخلفية: بطاقتان في الشاشة تُقرأ منهما، فلا عدّاد موازٍ في الواجهة */
    val transfers = queue.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudTransferQueueSnapshot())

    /**
     * فتح الشاشة يُسلّح الطابور إن وجد فيه ما لم يكتمل: بعد قسوة النظام (إلغاء السلسلة أو
     * موت العملية) قد يبقى منتظرون بلا أي طلب مجدول؛ هذا الخطاف يضمن أن مجرد النظر إلى
     * الحالة يحركها، ولا يُبطل «الإيقاف المؤقت» الذي اختاره المستخدم.
     */
    init {
        viewModelScope.launch {
            val snapshot = queue.snapshot()
            if (snapshot.pending.isNotEmpty() && !snapshot.paused) scheduler.enqueueTransfers()
        }
    }

    /** إنشاء مجلد داخل السحابة: parentKey = null يعني المستوى الرئيسي للشاشة. */
    fun createFolder(name: String, parentKey: String?) {
        viewModelScope.launch {
            manager.createRemoteFolder(name, parentKey).fold(
                onSuccess = { messenger.notify("أُنشئ المجلد «$name» في السحابة") },
                onFailure = { messenger.notifyError(it.message ?: "تعذّر إنشاء المجلد") }
            )
        }
    }

    fun refresh() {
        viewModelScope.launch { manager.scanRemoteFilesAndSyncMetadata(false) }
    }

    /**
     * تنزيل محدد: يُدرج في طابور الخلفية بدل أن يُنفَّذ داخل الشاشة، فيستمر بعد إغلاق
     * التطبيق ويُلحق تلقائيًا عند عودة الاتصال. الوجهة تُجمَّد مع الطلب كما اختارها
     * المستخدم، فلا تتغير الوجهة لاحقًا بالخطأ إن بدل المستخدم مجلده أثناء الانتظار.
     */
    fun download(files: List<RemoteCloudFile>, destination: CloudDownloadDestination) {
        val title = if (files.size == 1) "تنزيل ${files.first().fullDisplayName}" else "تنزيل ${files.size} ملفًا من السحابة"
        enqueue(CloudTransferBatch(
            id = UUID.randomUUID().toString(),
            kind = CloudTransferKind.DOWNLOAD,
            title = title,
            remoteKeys = files.map { it.remoteKey },
            destination = destination
        ), "أُضيف إلى تنزيل الخلفية")
    }

    fun downloadFolder(key: String, destination: CloudDownloadDestination) {
        val name = manager.remoteFolders.value.firstOrNull { it.key == key }?.name ?: "المجلد المحدد"
        enqueue(CloudTransferBatch(
            id = UUID.randomUUID().toString(),
            kind = CloudTransferKind.DOWNLOAD,
            title = "تنزيل مجلد $name",
            remoteFolderKeys = setOf(key),
            destination = destination
        ), "أُضيف تنزيل المجلد إلى الخلفية")
    }

    private fun enqueue(batch: CloudTransferBatch, confirmation: String) {
        viewModelScope.launch {
            queue.enqueue(batch)
            scheduler.enqueueTransfers()
            messenger.notify(confirmation + " — يمكنك إغلاق التطبيق وسيكمل النظام")
        }
    }

    /**
     * أزرار الطابور كلها مفوّضة إلى [CloudTransferControls] — نفس ما يفعله زرّ الإشعار حرفيًا.
     * التكرار كان عطلًا فعليًا: الواجهة ترفع راية يقرأها العامل بين الدفعتين فقط، فيبدو الزر
     * ميتًا أمام عنصرٍ طويل جارٍ؛ المتحكِّم يُلغي النقل الجاري أيضًا فينقطع فورًا ويُستأنف
     * من مقطعه.
     */
    fun pauseTransfers() {
        viewModelScope.launch { controls.pause() }
    }

    fun resumeTransfers() {
        viewModelScope.launch { controls.resume() }
    }

    /** إعادة المحاولة للاستثناءات فقط — لا تُعيد ما اكتمل ولا تُلغي المنجَز */
    fun retryFailedTransfers() {
        viewModelScope.launch { controls.retryFailed() }
    }

    /** إلغاء ما لم يبدأ فقط */
    fun cancelQueued() {
        viewModelScope.launch { controls.cancelQueued() }
    }

    /**
     * إسقاط استثناء يقبله المستخدم (ملف اختفى من الجهاز مثلًا) حتى لا يبقى في البطاقة يُعاد
     * ولا يُنسى. لا يمسّ بقية الاستثناءات ولا يرجع عن أي نقل منجز.
     */
    fun dismissFailedBatch(batchId: String) {
        viewModelScope.launch { controls.dismissFailed(batchId) }
    }



    /** إعادة تسمية ملف داخل السحابة (الاسم والامتداد في البيان؛ المحتوى والمفتاح ثابتان). */
    fun renameFile(remoteKey: String, newName: String) {
        viewModelScope.launch {
            manager.renameRemoteFile(remoteKey, newName).fold(
                onSuccess = { messenger.notify("أُعيدت تسمية الملف في السحابة") },
                onFailure = { messenger.notifyError(it.message ?: "تعذّرت إعادة التسمية") }
            )
        }
    }

    /** تغيير مسار ملفات: نقلها إلى مجلد سحابي آخر (null = المستوى الرئيسي). */
    fun moveFiles(files: List<RemoteCloudFile>, destinationFolderKey: String?) {
        viewModelScope.launch {
            manager.moveRemoteFiles(files.map { it.remoteKey }, destinationFolderKey).fold(
                onSuccess = { moved ->
                    if (moved == 0) messenger.notify("لا يلزم نقل؛ الملفات في الوجهة نفسها")
                    else messenger.notify("تغيّر مسار ${Formatters.fileCountLabel(moved)} في السحابة")
                },
                onFailure = { messenger.notifyError(it.message ?: "تعذّر تغيير المسار") }
            )
        }
    }

    /** إعادة تسمية مجلد سحابي أنشأه التطبيق؛ مجلدات المسار المادي لا تُعاد تسميتها هنا. */
    fun renameFolder(folderKey: String, newName: String) {
        viewModelScope.launch {
            manager.renameRemoteFolder(folderKey, newName).fold(
                onSuccess = { messenger.notify("أُعيدت تسمية المجلد في السحابة") },
                onFailure = { messenger.notifyError(it.message ?: "تعذّرت إعادة تسمية المجلد") }
            )
        }
    }

    /**
     * حذف ملفات مختارة من السحابة. **النسخ المحلية على الجهاز لا تُحذف** — الرسالة
     * النهائية هنا صريحة بهذا حتى لا يفترض المستخدم أن حذفه السحابي محا جهازه.
     */
    fun deleteFiles(files: List<RemoteCloudFile>) {
        viewModelScope.launch {
            manager.deleteRemoteFiles(files.map { it.remoteKey }).fold(
                onSuccess = { count -> messenger.notify("حُذف ${Formatters.fileCountLabel(count)} من السحابة؛ نسخك المحلية كما هي") },
                onFailure = { messenger.notifyError(it.message ?: "تعذّر الحذف من السحابة") }
            )
        }
    }

    /** حذف مجلد سحابي أنشأه التطبيق (فارغ فقط) من داخل شاشة السحابة. */
    fun deleteFolder(folder: RemoteCloudFolder) {
        viewModelScope.launch {
            manager.deleteRemoteFolder(folder.key).fold(
                onSuccess = { messenger.notify("حُذف المجلد من السحابة") },
                onFailure = { messenger.notifyError(it.message ?: "تعذّر حذف المجلد") }
            )
        }
    }
}
