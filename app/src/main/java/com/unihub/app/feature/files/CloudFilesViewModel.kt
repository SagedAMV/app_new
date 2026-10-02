package com.unihub.app.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.Formatters
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.RemoteCloudFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
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
    private val authManager: CloudAuthManager
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

    fun refresh() {
        viewModelScope.launch { manager.scanRemoteFilesAndSyncMetadata(false) }
    }

    fun download(files: List<RemoteCloudFile>, destination: CloudDownloadDestination) {
        viewModelScope.launch {
            manager.downloadSelectedFiles(files, destination = destination)
                .onFailure { messenger.notifyError(it.message ?: "تعذّر التنزيل من السحابة") }
        }
    }

    fun downloadFolder(key: String, destination: CloudDownloadDestination) {
        viewModelScope.launch {
            manager.downloadFolder(key, destination)
                .onFailure { messenger.notifyError(it.message ?: "تعذّر تنزيل المجلد من السحابة") }
        }
    }

    fun cancel() = manager.cancelDownloads()

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
