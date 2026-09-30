package com.unihub.app.feature.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.cloud.CloudDownloadDestination
import com.unihub.app.data.cloud.CloudSyncManager
import com.unihub.app.data.cloud.RemoteCloudFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * حالة شاشة السحابة المستقلة [CloudFilesScreen]: يغلّف [CloudSyncManager] المشترك
 * (Singleton واحد لكل التطبيق) ولا يحتفظ بأي حالة خاصة به — فالشاشة تقرأ منه
 * مباشرةً بمصدر حقيقة واحد، وأي شاشة أخرى تفتحها ترى الحالة ذاتها بلا مزامنة.
 *
 * ملاحظة جولة التحويل من اللوحة المنبثقة إلى الشاشة: أُزيلت أربع خصائص كانت
 * تُمرَّر إلى اللوحة السابقة ولم تكن مستعملة أصلاً (busy, downloading, progress,
 * error) التزاماً بقاعدة «لا كود ميت» — إن احتجت لاحقاً عرض تقدّم لكل ملف أو
 * نص خطأ مفصّل فمصادرها باقية في [CloudSyncManager] وتُعاد بسهولة.
 */
@HiltViewModel
class CloudFilesViewModel @Inject constructor(private val manager: CloudSyncManager) : ViewModel() {

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
        viewModelScope.launch { manager.downloadSelectedFiles(files, destination = destination) }
    }

    fun downloadFolder(key: String, destination: CloudDownloadDestination) {
        viewModelScope.launch { manager.downloadFolder(key, destination) }
    }

    fun cancel() = manager.cancelDownloads()
}

/**
 * بوابة «افتح السحابة» القادمة من إشعار الملفات الجديدة (عدّاد الطلبات في
 * [com.unihub.app.MainActivity]).
 *
 * كانت تفتح اللوحة المنبثقة فوق أية وجهة مفتوحة؛ وبعد تحويل السحابة إلى وجهة
 * تنقل مستقلة صارت وظيفتها تحويل الطلب إلى تنقل عبر [onOpen] — لا تملك حالة
 * ولا مرسوماً بصرياً، والملاحة تُنفَّذ بمعرّفات آمنة الأنواع في مخطط التنقل.
 */
@Composable
fun CloudNotificationPickerHost(request: Int, onOpen: () -> Unit) {
    LaunchedEffect(request) {
        if (request > 0) onOpen()
    }
}
