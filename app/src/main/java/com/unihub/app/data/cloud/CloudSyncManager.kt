package com.unihub.app.data.cloud

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.storage.StorageManager
import android.util.Log
import com.unihub.app.core.common.Formatters
import com.unihub.app.core.prefs.CloudSyncPreferences
import com.unihub.app.core.prefs.CloudSyncSettings
import com.unihub.app.data.auth.AuthPermission
import com.unihub.app.data.auth.CloudAuthManager
import com.unihub.app.data.backup.BackupRepository
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.notifications.CloudFileNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLConnection
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class CloudDownloadProgress(val downloadedBytes: Long, val totalBytes: Long)
data class CloudDownloadReport(val downloadedCount: Int, val failedNames: List<String>, val createdFolders: Int = 0) {
    val message: String get() = if (failedNames.isEmpty()) {
        if (downloadedCount == 0) "المجلدات جاهزة محلياً؛ لا توجد ملفات تحتاج التنزيل"
        else "تم سحب ${Formatters.fileCountLabel(downloadedCount)} وتخزينها محلياً"
    } else {
        "اكتمل $downloadedCount ملف، وتعذّر سحب ${failedNames.size}: ${failedNames.take(3).joinToString("، ")} — يمكنك إعادة المحاولة"
    }
}

/**
 * R2 ليس خادم Push لحظياً. نفحصه كل 20 ثانية فقط أثناء ظهور MainActivity،
 * وفي الخلفية بواسطة WorkManager (غير دقيق التوقيت). لا تنزل الفحوص أي محتوى ملف.
 * جميع عمليات R2 متسلسلة، وهوية الملف remoteKey + ETag، والاختيار يبدأ فارغاً.
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val r2Client: CloudflareR2Client,
    private val preferences: CloudSyncPreferences,
    private val scheduler: CloudSyncScheduler,
    private val notificationHelper: CloudFileNotificationHelper,
    private val catalog: CloudCatalogStore,
    private val authManager: CloudAuthManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    @Volatile private var foreground = false
    private var pulseJob: Job? = null
    @Volatile private var downloadJob: Job? = null
    @Volatile private var activeConnection = ""
    private var verificationJob: Job? = null
    private var stagingCleaned = false
    @Volatile private var notifyOnScan = true

    // لا نستدعي دالة تعدّل هذا التدفق قبل اكتمال تهيئته.
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    private val _verifyingKeys = MutableStateFlow<Set<String>>(emptySet())
    val verifyingKeys: StateFlow<Set<String>> = _verifyingKeys.asStateFlow()
    private val _localVerification = MutableStateFlow(CloudLocalVerification())
    val localVerification: StateFlow<CloudLocalVerification> = _localVerification.asStateFlow()
    private val _localFolders = MutableStateFlow<List<com.unihub.app.data.local.entity.FolderEntity>>(emptyList())
    val localFolders: StateFlow<List<com.unihub.app.data.local.entity.FolderEntity>> = _localFolders.asStateFlow()
    private val _allRemoteFiles = MutableStateFlow<List<RemoteCloudFile>>(emptyList())
    val allRemoteFiles: StateFlow<List<RemoteCloudFile>> = _allRemoteFiles.asStateFlow()
    private val _remoteFolders = MutableStateFlow<List<RemoteCloudFolder>>(emptyList())
    val remoteFolders: StateFlow<List<RemoteCloudFolder>> = _remoteFolders.asStateFlow()
    private val _transferState = MutableStateFlow(CloudTransferState())
    val transferState: StateFlow<CloudTransferState> = _transferState.asStateFlow()
    private val _lastUploadReport = MutableStateFlow<CloudUploadReport?>(null)
    val lastUploadReport: StateFlow<CloudUploadReport?> = _lastUploadReport.asStateFlow()
    private val scanController = CloudScanController(scope, SCAN_TIMEOUT_MS,
        counts = { scan: Scan -> scan.state.files.size to scan.state.folders.size },
        fetch = { performScan() })
    val scanState: StateFlow<CloudScanState> = scanController.state
    private val _availableRemoteFiles = MutableStateFlow<List<RemoteCloudFile>>(emptyList())
    val availableRemoteFiles: StateFlow<List<RemoteCloudFile>> = _availableRemoteFiles.asStateFlow()
    private val _downloadingKeys = MutableStateFlow<Set<String>>(emptySet())
    val downloadingKeys: StateFlow<Set<String>> = _downloadingKeys.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Map<String, CloudDownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, CloudDownloadProgress>> = _downloadProgress.asStateFlow()
    private val _lastDownloadReport = MutableStateFlow<CloudDownloadReport?>(null)
    val lastDownloadReport: StateFlow<CloudDownloadReport?> = _lastDownloadReport.asStateFlow()
    private val _lastScanError = MutableStateFlow<String?>(null)
    val lastScanError: StateFlow<String?> = _lastScanError.asStateFlow()

    fun startMonitoring() {
        if (!started.compareAndSet(false, true)) return
        checkIsOnline()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { refreshNetwork() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refreshNetwork() }
                override fun onLost(network: Network) { refreshNetwork() }
                private fun refreshNetwork() {
                    val wasOnline = _isOnline.value
                    if (checkIsOnline() && !wasOnline) scope.launch {
                        val settings = preferences.snapshot()
                        if (settings.autoSyncEnabled && settings.isConfigured) {
                            if (foreground) syncWithServer() else scheduler.enqueueSyncWhenConnected()
                        }
                        // طابور النقل لا يتبع autoSyncEnabled: كل دفعة فيه طلب صريح من المستخدم.
                        // بدون هذا السطر تبقى الدفعات «قيد الانتظار» إلى الأبد بعد انقطاع يوقف
                        // العامل (العامل الموقوف تُهمَل نتيجته، فلا أحد يعيد الجدولة).
                        scheduler.enqueueTransfers()
                    }
                }
            })
        }.onFailure { Log.w(TAG, "تعذّر تسجيل مراقب الشبكة", it) }
        scope.launch {
            preferences.settings.map { it.credentials to it.autoSyncEnabled }.distinctUntilChanged().collect { (creds, auto) ->
                mutex.withLock {
                    val state = ensureConnection(creds)
                    refreshAvailable(state)
                }
                if (auto && creds.isConfigured) scheduler.ensurePeriodicSync() else scheduler.cancelAll()
            }
        }
    }

    /** MainActivity.onStart/onStop، وليس حلقة تعمل للأبد بعد إغلاق الواجهة. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (!visible) {
            pulseJob?.cancel()
            pulseJob = null
            return
        }
        if (pulseJob?.isActive == true) return
        pulseJob = scope.launch {
            while (isActive) {
                val settings = preferences.snapshot()
                if (settings.isConfigured && checkIsOnline()) {
                    authManager.verifyActiveSessionWithCloud()
                    if (settings.autoSyncEnabled && authManager.isAuthenticatedNow()) {
                        syncWithServer()
                    }
                }
                delay(if (scanState.value.phase == CloudScanPhase.EMPTY) EMPTY_PULSE_INTERVAL_MS else LIVE_PULSE_INTERVAL_MS)
            }
        }
    }

    suspend fun onLocalDataChanged() {
        preferences.markLocalChange()
        val settings = preferences.snapshot()
        if (!settings.autoSyncEnabled || !settings.isConfigured || !authManager.isAuthenticatedNow()) return
        scheduler.enqueueSyncWhenConnected()
        if (foreground && checkIsOnline()) syncWithServer()
    }

    /** طلبات الفحص تشترك في طلب واحد، ولا تبدأ رفع ملفات أو تنتظر نقلاً طويلاً. */
    suspend fun scanRemoteFilesAndSyncMetadata(showNotificationIfNew: Boolean = true): Result<List<RemoteCloudFile>> {
        notifyOnScan = showNotificationIfNew
        return scanController.execute(showLoading = true).map { it.available }
    }

    private suspend fun performScan(): Scan = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) throw CloudBusyException()
        _isSyncing.value = true
        try {
            val settings = preferences.snapshot()
            requireConnection(settings)
            authManager.verifyActiveSessionWithCloud()
            if (!authManager.isAuthenticatedNow()) {
                throw IOException("يجب تسجيل الدخول أولاً للوصول إلى السحابة")
            }
            scanLocked(settings, notifyOnScan)
        } finally { _isSyncing.value = false; mutex.unlock() }
    }

    /** التلقائي يزامن النصوص فقط، ولا يرفع مكتبة الهاتف الضخمة ضمن فحص السحابة. */
    suspend fun syncWithServer(isManual: Boolean = false): Result<String> {
        val settings = preferences.snapshot()
        if (!isManual && !settings.autoSyncEnabled) return Result.success("المراقبة التلقائية متوقفة")
        notifyOnScan = true
        val result = scanController.execute(showLoading = isManual)
        val scan = result.getOrElse { return Result.failure(it) }
        val currentUser = authManager.currentAuthenticatedUser()
        val canAutoPushMetadata = currentUser != null &&
            currentUser.effectivePermissions.canUpload &&
            currentUser.effectivePermissions.canModify
        if (canAutoPushMetadata && (settings.pendingUpload || needsLightweightUpload(scan.state))) {
            val sent = pushLightweightMetadata(scan)
            if (sent.isFailure && sent.exceptionOrNull() !is CloudBusyException) {
                _lastScanError.value = sent.exceptionOrNull()?.message
            }
        }
        return Result.success(scanState.value.message)
    }

    /** الاسم القديم يحتفظ بتوافق واجهة النسخ الاحتياطي؛ يرسل النصوص فقط الآن. */
    suspend fun pushToServer(allowEmptyUpload: Boolean = false): Result<Int> {
        authManager.verifyActiveSessionWithCloud()
        authManager.requirePermission(AuthPermission.UPLOAD).getOrElse { return Result.failure(it) }
        authManager.requirePermission(AuthPermission.MODIFY).getOrElse { return Result.failure(it) }
        val scan = scanController.execute().getOrElse { return Result.failure(it) }
        return pushLightweightMetadata(scan)
    }

    suspend fun prepareUploadPlan(fileIds: Set<Long> = emptySet(), folderIds: Set<Long> = emptySet(), title: String = "رفع المحدد"): CloudUploadPlan = withContext(Dispatchers.IO) {
        val records = backupRepository.getLocalFileRecords()
        val plan = CloudFolderTree.plan(title, records, backupRepository.getLocalFolders(), fileIds, folderIds)
        val selected = records.filter { it.id in plan.fileIds }
        plan.copy(totalBytes = selected.filter { File(it.filePath).isFile }.sumOf { File(it.filePath).length() },
            missingFiles = selected.filterNot { File(it.filePath).isFile }.map { it.name })
    }

    fun cancelDownloads() { downloadJob?.cancel() }

    suspend fun downloadFolder(key: String, destination: CloudDownloadDestination = CloudDownloadDestination()): Result<CloudDownloadReport> = downloadSelectedFiles(
        CloudFolderTree.filesWithin(_allRemoteFiles.value, _remoteFolders.value, key), setOf(key), destination)

    suspend fun downloadSelectedFiles(selectedFiles: List<RemoteCloudFile>, selectedFolderKeys: Set<String> = emptySet(), destination: CloudDownloadDestination = CloudDownloadDestination()): Result<CloudDownloadReport> = operation(CloudTransferKind.DOWNLOAD) {
        if (selectedFiles.isEmpty() && selectedFolderKeys.isEmpty()) return@operation CloudDownloadReport(0, emptyList())
        val settings = preferences.snapshot()
        requireConnection(settings)
        authManager.verifyActiveSessionWithCloud()
        authManager.requirePermission(AuthPermission.DOWNLOAD).getOrThrow()
        val scan = boundedScanLocked(settings, false)
        val chosenLocal = if (destination.location == CloudDownloadLocation.ORIGINAL_CLOUD_TREE || destination.localFolderId == null) null else {
            backupRepository.getLocalFolders().firstOrNull { it.id == destination.localFolderId &&
                (destination.localFolderCreatedAt == null || it.createdAt == destination.localFolderCreatedAt) }
                ?: throw IOException("المجلد الذي اخترته لم يعد موجوداً؛ اختر وجهة أخرى")
        }
        val anchor = selectedFolderKeys.singleOrNull()
        val folderKeys = selectedFolderKeys.flatMap { CloudFolderTree.descendants(scan.state.folders, it) }.toSet()
        suspend fun resolveDestination(key: String?): com.unihub.app.data.local.entity.FolderEntity? {
            return when (destination.location) {
                CloudDownloadLocation.ORIGINAL_CLOUD_TREE -> ensureFolderPath(key, scan.state.folders)
                CloudDownloadLocation.LOCAL_FOLDER -> chosenLocal
                CloudDownloadLocation.FOLDER_INSIDE_LOCAL -> {
                    var parent = chosenLocal
                    val relative = CloudDownloadPlacement.relativeFolders(scan.state.folders, key, anchor)
                    for ((position, folder) in relative.withIndex()) {
                        val localName = if (position == 0) CloudDownloadPlacement.localRootName(destination, folder) else folder.name
                        val placementKey = "placement:${destination.localFolderId}:${destination.localFolderCreatedAt}:${anchor ?: relative.firstOrNull()?.key}:${destination.rootFolderName}:${folder.key}"
                        if (position == 0 && CloudDownloadPlacement.shouldReuseChosenFolderAsRoot(chosenLocal, localName)) {
                            val reused = chosenLocal ?: continue
                            parent = reused
                            catalog.update(activeConnection) { it.copy(folderLinks = it.folderLinks.filterNot { old -> old.remoteKey == placementKey } +
                                CloudFolderLink(reused.id, reused.createdAt, placementKey)) }
                            continue
                        }
                        val state = catalog.snapshot(activeConnection)
                        val link = state.folderLinks.firstOrNull { it.remoteKey == placementKey }
                        parent = backupRepository.ensureDownloadedFolder(folder.copy(key = placementKey, name = localName, legacyId = null), parent?.id, link)
                        val result = parent
                        catalog.update(activeConnection) { it.copy(folderLinks = it.folderLinks.filterNot { old -> old.remoteKey == placementKey } +
                            CloudFolderLink(result.id, result.createdAt, placementKey)) }
                    }
                    parent
                }
            }
        }
        val jobContext = currentCoroutineContext()
        suspend fun matchExistingInDestination(
            target: RemoteCloudFile,
            folderId: Long?,
            expectedSha256: String
        ): FileEntity? {
            val existingLocalFiles = backupRepository.getExistingLocalFiles()
            val candidateWithoutHash = CloudDownloadPlacement.findExistingLocalFile(
                localFiles = existingLocalFiles,
                name = target.name,
                extension = target.extension,
                size = target.size,
                targetFolderId = folderId
            ) ?: return null
            val hasStrongSha = CloudPresenceMatcher.strongHash(expectedSha256)
            val matched = if (!hasStrongSha) {
                candidateWithoutHash
            } else {
                val state = catalog.snapshot(activeConnection)
                val sameFolderCandidates = existingLocalFiles.filter {
                    it.folderId == folderId && it.size == target.size
                }
                val localHashes = mutableMapOf<Long, String>()
                for (candidate in sameFolderCandidates) {
                    val disk = File(candidate.filePath)
                    if (!disk.isFile) continue
                    val stat = CloudLocalFileStat(candidate.id, candidate.createdAt, candidate.filePath, disk.length(), disk.lastModified())
                    val cachedFp = state.localFingerprints.firstOrNull { CloudPresenceMatcher.validFingerprint(it, stat) }
                    val sha = cachedFp?.sha256 ?: r2Client.sha256Hex(disk) { jobContext.ensureActive() }.also { computed ->
                        val fp = CloudLocalFingerprint(stat.id, stat.createdAt, stat.path, stat.size, stat.modifiedAt, computed)
                        catalog.update(activeConnection) { current ->
                            current.copy(localFingerprints = current.localFingerprints.filterNot { old -> old.localId == stat.id } + fp)
                        }
                    }
                    localHashes[candidate.id] = sha
                }
                CloudDownloadPlacement.findExistingLocalFile(
                    localFiles = existingLocalFiles,
                    name = target.name,
                    extension = target.extension,
                    size = target.size,
                    targetFolderId = folderId,
                    remoteSha256 = expectedSha256,
                    localSha256ById = localHashes
                )
            } ?: return null
            val disk = File(matched.filePath)
            val linkSha = expectedSha256.takeIf { it.isNotBlank() }
                ?: catalog.snapshot(activeConnection).localFingerprints.firstOrNull { it.localId == matched.id }?.sha256.orEmpty()
            val link = CloudFileLink(
                localId = matched.id,
                remoteKey = target.remoteKey,
                remoteVersion = target.versionToken,
                sha256 = linkSha,
                localSize = disk.length(),
                localModifiedAt = disk.lastModified(),
                localCreatedAt = matched.createdAt
            )
            catalog.update(activeConnection) {
                it.copy(
                    links = it.links.filterNot { old -> old.localId == matched.id || old.remoteKey == target.remoteKey } + link,
                    notifiedVersions = it.notifiedVersions + target.notificationToken
                )
            }
            refreshAvailable(catalog.snapshot(activeConnection))
            return matched
        }
        val choices = selectedFiles.distinctBy { it.remoteKey }
        val totalBytes = choices.filter { choice -> scan.available.any { it.remoteKey == choice.remoteKey && it.versionToken == choice.versionToken } }.fold(0L) { total, file -> Math.addExact(total, file.size.coerceAtLeast(0)) }
        if (freeSpaceForNewData() - RESERVE_BYTES < totalBytes) {
            throw IOException("المساحة المتاحة لا تكفي للمحدد (${Formatters.fileSize(totalBytes)})؛ اختر ملفات أقل أو حرّر مساحة")
        }
        if (CloudDownloadPlacement.shouldCreateCloudFolders(destination)) for (key in folderKeys) resolveDestination(key)
        _lastDownloadReport.value = null
        downloadJob = jobContext.job
        val failed = mutableListOf<String>()
        var completed = 0
        var bytesCompletedBeforeCurrentFile = 0L
        try {
            for ((index, chosen) in choices.withIndex()) {
                currentCoroutineContext().ensureActive()
                val remote = scan.available.firstOrNull { it.remoteKey == chosen.remoteKey }
                if (remote == null) {
                    val serverFile = scan.state.files.firstOrNull {
                        it.remoteKey == chosen.remoteKey && it.versionToken == chosen.versionToken
                    }
                    if (serverFile != null) {
                        val preParent = resolveDestination(serverFile.cloudFolderKey)
                        if (matchExistingInDestination(serverFile, preParent?.id, serverFile.sha256) != null) {
                            continue
                        }
                    }
                    // الملف قد اكتمل تنزيله من واجهة أخرى؛ لا ننزله مرتين.
                    if (chosen.remoteKey in _verifyingKeys.value) {
                        failed += chosen.fullDisplayName + " (لم تكتمل مقارنة النسخة المحلية بعد)"
                        continue
                    }
                    if (serverFile != null) continue
                    failed += chosen.fullDisplayName
                    continue
                }
                if (remote.versionToken != chosen.versionToken) {
                    failed += chosen.fullDisplayName + " (تغيّرت نسخته؛ أعد الاختيار)"
                    continue
                }
                val parent = resolveDestination(remote.cloudFolderKey)
                if (matchExistingInDestination(remote, parent?.id, remote.sha256) != null) {
                    // حجم الملف داخل الإجمالي، لكنه موجود محليًا بالفعل؛ اعتبره متجاوزًا بأمان.
                    _transferState.value = CloudTransferState(
                        kind = CloudTransferKind.DOWNLOAD,
                        fileName = remote.fullDisplayName,
                        index = index + 1,
                        totalFiles = choices.size,
                        bytesDone = remote.size,
                        bytesTotal = remote.size,
                        bytesCompletedBeforeCurrentFile = bytesCompletedBeforeCurrentFile,
                        batchBytesTotal = totalBytes
                    )
                    bytesCompletedBeforeCurrentFile = (bytesCompletedBeforeCurrentFile + remote.size).coerceAtMost(totalBytes)
                    continue
                }
                val staging = File(context.filesDir, "cloud-downloads").apply { mkdirs() }
                // اسم ثابت لكل مفتاح سحابي — لا createTempFile العشوائي: حتى يجد الاستئناف
                // بعد انقطاع الشبكة (أو بعد إعادة تشغيل التطبيق) جزأه ويستكمل من نصفه.
                val temp = CloudDownloadPart.fileFor(staging, remote.remoteKey)
                val resumedBytes = (if (temp.isFile) temp.length() else 0L).coerceIn(0L, remote.size.coerceAtLeast(0L))
                _transferState.value = CloudTransferState(
                    kind = CloudTransferKind.DOWNLOAD,
                    fileName = remote.fullDisplayName,
                    index = index + 1,
                    totalFiles = choices.size,
                    bytesDone = resumedBytes,
                    bytesTotal = remote.size,
                    bytesCompletedBeforeCurrentFile = bytesCompletedBeforeCurrentFile,
                    batchBytesTotal = totalBytes
                )
                _downloadingKeys.value = setOf(remote.remoteKey)
                _downloadProgress.value = mapOf(remote.remoteKey to CloudDownloadProgress(resumedBytes, remote.size))
                var keepPart = false
                try {
                    val remaining = remote.size - (if (temp.isFile) temp.length() else 0L)
                    if (freeSpaceForNewData() - RESERVE_BYTES < remaining.coerceAtLeast(0L)) throw IOException("المساحة لا تكفي")
                    var downloadedHash = ""
                    val found = r2Client.downloadFile(
                        settings.credentials, remote.remoteKey, temp, remote.etag, remote.size,
                        onDigest = { downloadedHash = it },
                        resume = true
                    ) { bytes, total ->
                        _downloadProgress.value = mapOf(remote.remoteKey to CloudDownloadProgress(bytes, total))
                        _transferState.value = _transferState.value.copy(bytesDone = bytes, bytesTotal = total)
                    }.getOrThrow()
                    if (!found) throw IOException("الملف لم يعد موجوداً على الخادم")
                    val hash = downloadedHash.takeIf { it.isNotBlank() }
                        ?: throw IOException("لم تكتمل بصمة التنزيل")
                    if (remote.sha256.isNotBlank() && hash != remote.sha256) throw IOException("بصمة الملف لا تطابق الفهرس")
                    if (matchExistingInDestination(remote, parent?.id, hash) != null) {
                        // اكتشفنا نسخة مطابقة بعد التنزيل؛ لا نحفظ نسخة مكررة لكن حجمها أُنجز.
                        bytesCompletedBeforeCurrentFile = (bytesCompletedBeforeCurrentFile + remote.size).coerceAtMost(totalBytes)
                        continue
                    }
                    val state = catalog.snapshot(activeConnection)
                    val previous = state.links.firstOrNull { it.remoteKey == remote.remoteKey }
                    val local = backupRepository.getExistingLocalFiles().firstOrNull {
                        it.id == previous?.localId && it.folderId == parent?.id
                    }
                    // إن عُدّلت النسخة المحلية، نحتفظ بها ونضيف السحابية كنسخة مستقلة.
                    val targetId = local?.takeIf {
                        val disk = File(it.filePath)
                        previous != null && it.createdAt == previous.localCreatedAt && disk.length() == previous.localSize &&
                            r2Client.sha256Hex(disk) { jobContext.ensureActive() } == previous.sha256
                    }?.id
                    // وجهة محلية صريحة، حتى عندما تكون المستوى الرئيسي (null)، لا ترجع إلى folderId البعيد.
                    val localRemote = remote.copy(folderId = parent?.id, folderName = parent?.name)
                    val saved = backupRepository.saveSelectedRemoteFile(localRemote, temp, targetId).getOrThrow()
                    val disk = File(saved.filePath)
                    val link = CloudFileLink(saved.id, remote.remoteKey, remote.versionToken, hash, disk.length(), disk.lastModified(), saved.createdAt)
                    catalog.update(activeConnection) {
                        it.copy(
                            links = it.links.filterNot { old -> old.localId == saved.id } + link,
                            notifiedVersions = it.notifiedVersions + remote.notificationToken
                        )
                    }
                    completed++
                    // نُشر آخر callback عادةً عند اكتمال البايتات؛ ثبّته صراحةً قبل الانتقال للملف التالي.
                    _transferState.value = _transferState.value.copy(bytesDone = remote.size, bytesTotal = remote.size)
                    refreshAvailable(catalog.snapshot(activeConnection))
                    bytesCompletedBeforeCurrentFile = (bytesCompletedBeforeCurrentFile + remote.size).coerceAtMost(totalBytes)
                } catch (cancelled: CancellationException) {
                    keepPart = true // مقاطعة المستخدم أو إيقاف النظام: الجزء للاستئناف لا للنفايات
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "تعذّر تنزيل ملف مختار", error)
                    failed += remote.fullDisplayName
                    if (!checkIsOnline()) {
                        failed += choices.drop(index + 1).map { it.fullDisplayName }
                        break
                    }
                } finally {
                    if (!keepPart) temp.delete()
                    _downloadingKeys.value = emptySet()
                    _downloadProgress.value = emptyMap()
                }
            }
        } finally {
            downloadJob = null
            _downloadingKeys.value = emptySet()
            _downloadProgress.value = emptyMap()
        }
        val report = CloudDownloadReport(completed, failed, if (CloudDownloadPlacement.shouldCreateCloudFolders(destination)) folderKeys.size else 0)
        _lastDownloadReport.value = report
        if (failed.isEmpty()) preferences.recordSyncSuccess(System.currentTimeMillis(), report.message)
        else preferences.recordSyncFailure(report.message)
        report
    }

    // ─── التحكم داخل السحابة (جولة تعليمات.md): إعادة تسمية ملفات، تغيير مساراتها،
    // وإعادة تسمية مجلدات — كلها تحرير لبيان الفهرس (Manifest) دون لمس المحتوى:
    // مفاتيح الكائنات محتوى‑العنوان (hash) والأسماء والمجلدات وصفات في البيان. ───

    /**
     * قفل تحرير عام للبيان: يتسلسل مع عمليات النقل عبر [mutex] نفسه، بمهلة خاصة أطول
     * من مهلة الفحص لأن العملية فحص + نشر. لا يرفع حالة نقل (لا بطاقة تقدم للمستخدم).
     */
    private suspend fun <T> editRemoteMetadata(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext Result.failure(CloudBusyException())
        _isSyncing.value = true
        try {
            withTimeout(METADATA_EDIT_TIMEOUT_MS) {
                cloudAttempt {
                    authManager.verifyActiveSessionWithCloud()
                    authManager.requirePermission(AuthPermission.MODIFY).getOrThrow()
                    block()
                }
            }
        } catch (_: TimeoutCancellationException) { Result.failure(CloudScanTimeoutException()) }
        finally { _isSyncing.value = false; mutex.unlock() }
    }

    /**
     * نشر تعديل على بيان الفهرس فوق آخر نسخة خادم (If-Match يصدّ التعديل المتزامن).
     * يبدأ من بيان الخادم نفسه — لا يمزج النصوص المحلية حتى لا يتحول تحرير سحابي
     * إلى رفع نصي غير مقصود — ثم يعيد اكتشاف الملفات والمجلدات وينعش الواجهات.
     */
    private suspend fun publishManifestEdit(settings: CloudSyncSettings, scan: Scan, mutate: (JSONObject) -> Unit) {
        if (scan.metadataConflicts.isNotEmpty()) {
            throw IOException("يوجد تعارض في البيانات النصية مع الخادم؛ نفّذ «فحص ومزامنة» أولاً ثم أعد المحاولة")
        }
        val root = scan.remoteManifest?.let { JSONObject(it.toString()) } ?: JSONObject().apply {
            put("app", "unihub")
            put("schemaVersion", BackupRepository.SCHEMA_VERSION)
            put("storageMode", "selective-v2")
        }
        mutate(root)
        root.put("exportedAt", System.currentTimeMillis())
        val text = root.toString()
        val uploaded = r2Client.uploadText(
            settings.credentials, CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY, text,
            ifMatch = scan.manifestEtag.takeIf { it.isNotBlank() },
            ifNoneMatch = scan.remoteManifest == null
        ).getOrThrow()
        val discovered = discoverFiles(scan.objects, root)
        val folders = CloudFolderTree.foldersFromManifest(root, discovered, scan.objects)
        catalog.update(activeConnection) {
            it.copy(files = discovered, folders = folders, metadataBaseline = text, metadataEtag = uploaded.etag)
        }
        refreshAvailable(catalog.snapshot(activeConnection))
    }

    /**
     * إعادة تسمية ملف داخل السحابة: يعدّل الاسم والامتداد (وبالتالي النوع وصيغة MIME)
     * في البيان فقط؛ مفتاح المحتوى وبصمته ثابتان فلا تتأثر النسخ المحلية المرتبطة.
     * الملفات غير المسجلة في البيان تُتبنّى تلقائياً بإدخال وصفها لأول مرة.
     */
    suspend fun renameRemoteFile(remoteKey: String, newNameWithExtension: String): Result<Unit> = editRemoteMetadata {
        val settings = preferences.snapshot()
        requireConnection(settings)
        val scan = boundedScanLocked(settings, false)
        val current = scan.state.files.firstOrNull { it.remoteKey == remoteKey }
            ?: throw IOException("الملف لم يعد في قائمة السحابة؛ اضغط فحص ثم أعد المحاولة")
        val cleaned = newNameWithExtension.trim().trim('.')
        if (cleaned.isBlank() || cleaned.contains('/')) throw IOException("اسم غير صالح: لا يمكن أن يكون فارغاً أو يحوي /")
        val dot = cleaned.lastIndexOf('.')
        val name = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val extension = (if (dot > 0) cleaned.substring(dot + 1) else current.extension)
            .lowercase(Locale.US).filter { it.isLetterOrDigit() }
            .ifBlank { current.extension.ifBlank { "bin" } }
        val updated = current.copy(
            name = name, extension = extension,
            kind = FileKind.fromExtension(extension), mimeType = mimeType(extension)
        )
        publishManifestEdit(settings, scan) { root ->
            root.put("files", CloudManifestTools.mergeFiles(root.optJSONArray("files"), listOf(updated)))
        }
    }

    /**
     * تغيير مسار ملفات في السحابة: نقلها إلى مجلد سحابي آخر بتعديل cloudFolderKey
     * في البيان. الوجهة المسموحة: مجلد بيان (folder:) أو مجلد مسار (path:) أو
     * المستوى الرئيسي (null). مفاتيح legacy: مرتبطة بالمكتبة المحلية ولا تُستخدم وجهة.
     */
    suspend fun moveRemoteFiles(remoteKeys: List<String>, destinationFolderKey: String?): Result<Int> = editRemoteMetadata {
        val settings = preferences.snapshot()
        requireConnection(settings)
        val scan = boundedScanLocked(settings, false)
        if (destinationFolderKey != null) {
            if (!destinationFolderKey.startsWith("folder:") && !destinationFolderKey.startsWith("path:")) {
                throw IOException("لا يمكن النقل إلى هذا المجلد؛ اختر مجلداً من شجرة السحابة")
            }
            if (scan.state.folders.none { it.key == destinationFolderKey }) {
                throw IOException("مجلد الوجهة لم يعد متاحاً؛ اضغط فحص ثم أعد المحاولة")
            }
        }
        val moving = remoteKeys.distinct()
            .mapNotNull { key -> scan.state.files.firstOrNull { it.remoteKey == key } }
            .filter { it.cloudFolderKey != destinationFolderKey }
        if (moving.isEmpty()) return@editRemoteMetadata 0
        val destinationName = destinationFolderKey?.let { key -> scan.state.folders.firstOrNull { it.key == key }?.name }
        val updated = moving.map { it.copy(cloudFolderKey = destinationFolderKey, folderId = null, folderName = destinationName) }
        publishManifestEdit(settings, scan) { root ->
            root.put("files", CloudManifestTools.mergeFiles(root.optJSONArray("files"), updated))
        }
        moving.size
    }

    /**
     * إعادة تسمية مجلد سحابي أنشأه التطبيق (مفتاح folder:) — الاسم وصف في البيان
     * والمفتاح هوية ثابتة، فلا تتأثر روابط المجلدات المنزّلة محلياً.
     * مجلدات path: تعكس بنية التخزين الفعلية للكائنات، وlegacy: أسماء مجلدات محلية؛
     * كلاهما لا يُعاد تسميته من السحابة ورسالة الخطأ توضح السبب.
     */
    suspend fun renameRemoteFolder(folderKey: String, newName: String): Result<Unit> = editRemoteMetadata {
        val settings = preferences.snapshot()
        requireConnection(settings)
        val scan = boundedScanLocked(settings, false)
        if (!folderKey.startsWith("folder:")) {
            throw IOException("هذا المجلد مبني على مسار التخزين أو على مجلد محلي؛ لا يمكن إعادة تسميته من السحابة")
        }
        val cleaned = newName.trim()
        if (cleaned.isBlank() || cleaned.contains('/')) throw IOException("اسم غير صالح: لا يمكن أن يكون فارغاً أو يحوي /")
        val current = scan.state.folders.firstOrNull { it.key == folderKey }
            ?: throw IOException("المجلد لم يعد في قائمة السحابة؛ اضغط فحص ثم أعد المحاولة")
        publishManifestEdit(settings, scan) { root ->
            root.put("cloudFolders", CloudFolderTree.mergeFolders(root.optJSONArray("cloudFolders"), listOf(current.copy(name = cleaned))))
        }
    }

    /**
     * إنشاء مجلد داخل السحابة نفسها (تكافؤ ما توفره شاشة الملفات المحلية).
     *
     * المفتاح يُبنى بنفس نمط الرفع «folder:<UUID>» حتى يقرأه [CloudFolderTree.foldersFromManifest]
     * كما يقرأ بقية المجلدات المنشأة؛ ومجلد فارغ يبقى ظاهرًا لأنه يُنشر في بيان `cloudFolders`
     * لا على أنه كائن في الحاوية. الرفض صريح عند الاسم المكرر في المستوى نفسه — وإلا تتوالد
     * مجلدات متطابقة لا يستطيع المستخدم التمييز بينها ولا حذفها.
     */
    suspend fun createRemoteFolder(name: String, parentKey: String?): Result<Unit> = editRemoteMetadata {
        val settings = preferences.snapshot()
        requireConnection(settings)
        val scan = boundedScanLocked(settings, false)
        val cleaned = name.trim()
        if (cleaned.isBlank() || cleaned.contains('/')) throw IOException("اسم غير صالح: لا يمكن أن يكون فارغاً أو يحوي /")
        if (parentKey != null) {
            val parent = scan.state.folders.firstOrNull { it.key == parentKey }
                ?: throw IOException("المجلد الأب لم يعد في قائمة السحابة؛ اضغط فحص ثم أعد المحاولة")
            if (!parent.key.startsWith("folder:")) {
                throw IOException("لا يمكن إنشاء مجلد فرعي داخل مجلد مبني على مسار التخزين أو على مجلد محلي")
            }
        }
        if (scan.state.folders.any { it.parentKey == parentKey && it.name.trim() == cleaned }) {
            throw IOException("يوجد مجلد بالاسم نفسه في هذا المكان؛ اختر اسمًا آخر أو افتحه لرفع الملفات إليه")
        }
        val created = RemoteCloudFolder("folder:${UUID.randomUUID()}", cleaned, parentKey, System.currentTimeMillis())
        publishManifestEdit(settings, scan) { root ->
            root.put("cloudFolders", CloudFolderTree.mergeFolders(root.optJSONArray("cloudFolders"), listOf(created)))
        }
    }

    /**
     * حذف ملفات من السحابة نفسها (جولة تعليمات.md — تحكم كامل داخل الواجهة).
     *
     * **الترتيب مقصود** (قرار محاكمة الحلقة 5 — H1): تُحذف كائنات R2 أولاً ثم يُنظَّف بيان
     * الفهرس. السبب: [discoverFiles] يبني القائمة من كائنات الحاوية لا من سجلات البيان،
     * فحذف الكائن يُخفي الملف فوراً حتى لو تعارض نشر البيان مع جهاز آخر — بلا كائن يتيم
     * يُتبنّى تلقائياً فيعود إشعاراً كاذباً للمستخدم.
     *
     * **النسخ المحلية لا تُمس إطلاقاً** (ثابت Inv1): تُنقّى روابط الفهرس فقط، فيبقى ملف
     * المستخدم على جهازه كما هو ولو حُذفت نسخته السحابية.
     */
    suspend fun deleteRemoteFiles(remoteKeys: List<String>): Result<Int> = editRemoteMetadata {
        val settings = preferences.snapshot()
        requireConnection(settings)
        val scan = boundedScanLocked(settings, false)
        val targets = CloudDeleteRules.targets(scan.state.files, remoteKeys)
        if (targets.isEmpty()) {
            throw IOException("الملفات المطلوبة لم تعد في قائمة السحابة؛ اضغط فحص ثم أعد المحاولة")
        }
        if (targets.size > CloudDeleteRules.MAX_PER_OPERATION) {
            throw IOException("الحد الأقصى للحذف في العملية الواحدة ${CloudDeleteRules.MAX_PER_OPERATION} ملفاً؛ قسّم التحديد ثم أعد المحاولة")
        }
        val deleted = linkedSetOf<String>()
        val failed = mutableListOf<String>()
        for (file in targets) {
            r2Client.deleteObject(settings.credentials, file.remoteKey).fold(
                onSuccess = { deleted += file.remoteKey },
                onFailure = { failed += file.fullDisplayName }
            )
        }
        if (deleted.isEmpty()) {
            throw IOException("تعذّر حذف الملفات من السحابة: ${failed.take(3).joinToString("، ")}")
        }
        // الكائنات لم تعد موجودة فعلاً؛ فصل روابط النسخ المحلية يتم حتى لو تعارض نشر البيان.
        catalog.update(activeConnection) { it.copy(links = CloudDeleteRules.pruneLinks(it.links, deleted)) }
        try {
            publishManifestEdit(settings, scan) { root ->
                root.put("files", CloudManifestTools.withoutFiles(root.optJSONArray("files"), deleted))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // الحذف تمّ فعلاً واختفت الملفات من القائمة؛ الرسالة تصف الحالة بدقة بلا ادعاء فشل كامل.
            throw IOException("حُذف ${deleted.size} من السحابة، ولم يُحدَّث الفهرس (${error.message})؛ نفّذ «فحص ومزامنة»")
        } finally {
            refreshAvailable(catalog.snapshot(activeConnection))
        }
        if (failed.isNotEmpty()) {
            throw IOException("حُذف ${deleted.size}، وتعذّر ${failed.size}: ${failed.take(3).joinToString("، ")}")
        }
        deleted.size
    }

    /**
     * حذف مجلد سحابي أنشأه التطبيق (مفتاح folder:) بشرط أن يكون فارغاً من الملفات.
     * لا يُحذف أي كائن محتوى في هذه العملية — المجلد وصف في البيان، وملفاته (لو وُجدت)
     * تمنع الحذف برسالة تشرح المطلوب. ومجلدات path:/legacy: تُرفض لأنها تعكس بنية
     * التخزين أو مكتبة الجهاز المحلية.
     */
    suspend fun deleteRemoteFolder(folderKey: String): Result<Unit> = editRemoteMetadata {
        val settings = preferences.snapshot()
        requireConnection(settings)
        val scan = boundedScanLocked(settings, false)
        val folder = scan.state.folders.firstOrNull { it.key == folderKey }
            ?: throw IOException("المجلد لم يعد في قائمة السحابة؛ اضغط فحص ثم أعد المحاولة")
        CloudDeleteRules.folderDeletionBlockReason(folder, scan.state.files, scan.state.folders)
            ?.let { throw IOException(it) }
        val removed = CloudFolderTree.descendants(scan.state.folders, folderKey)
        publishManifestEdit(settings, scan) { root ->
            root.put("cloudFolders", CloudManifestTools.withoutFolders(root.optJSONArray("cloudFolders"), removed))
        }
        catalog.update(activeConnection) { it.copy(folderLinks = CloudDeleteRules.pruneFolderLinks(it.folderLinks, removed)) }
    }

    private data class Scan(
        val objects: List<R2ObjectSummary>,
        val remoteManifest: JSONObject?,
        val manifestEtag: String,
        val state: CloudCatalogSnapshot,
        val available: List<RemoteCloudFile>,
        val metadataConflicts: List<String>
    )

    private suspend fun scanLocked(settings: CloudSyncSettings, notify: Boolean): Scan {
        val previous = ensureConnection(settings.credentials)
        val objects = r2Client.listBucketObjects(settings.credentials).getOrThrow()
        val manifestObject = objects.firstOrNull { it.key == CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY }
        val remoteText = if (manifestObject == null) null else if (
            manifestObject.etag == previous.metadataEtag && previous.metadataBaseline != null
        ) previous.metadataBaseline else {
            r2Client.downloadTextObject(settings.credentials, manifestObject.key, manifestObject.etag).getOrThrow()?.text
                ?: throw CloudConflictException()
        }
        val remote = remoteText?.let { text -> JSONObject(text).also {
            if (it.optString("app") != "unihub" || it.optInt("schemaVersion", 1) > BackupRepository.SCHEMA_VERSION) {
                throw IOException("فهرس الخادم غير متوافق مع إصدار التطبيق")
            }
        } }
        val local = JSONObject(backupRepository.buildCloudManifestJson())
        val baseline = previous.metadataBaseline?.let(::JSONObject)
        val conflicts = CloudManifestTools.conflicts(local, remote, baseline)
        val merged = CloudManifestTools.mergeMetadata(local, remote, baseline)
            .put("folders", local.optJSONArray("folders") ?: JSONArray())
        val canPull = authManager.currentAuthenticatedUser()?.effectivePermissions?.canDownload == true
        if (canPull && !CloudManifestTools.metadataEqual(local, merged)) {
            backupRepository.syncLightweightMetadataFromManifest(merged.toString(), local.toString()).getOrThrow()
        }
        val discovered = discoverFiles(objects, remote)
        val remoteFolders = CloudFolderTree.foldersFromManifest(remote, discovered, objects)
        val tokens = discovered.mapTo(mutableSetOf()) { it.notificationToken }
        val newState = previous.copy(
            files = discovered,
            folders = remoteFolders,
            metadataBaseline = if (conflicts.isEmpty()) remoteText else previous.metadataBaseline,
            metadataEtag = if (conflicts.isEmpty()) manifestObject?.etag.orEmpty() else "",
            notifiedVersions = previous.notifiedVersions.intersect(tokens)
        )
        if (newState != previous) catalog.update(activeConnection) { current -> newState.copy(
            links = current.links, folderLinks = current.folderLinks, localFingerprints = current.localFingerprints,
            notifiedVersions = current.notifiedVersions.intersect(tokens)) }
        refreshAvailable(catalog.snapshot(activeConnection))
        if (notify && canPull) {
            val newFiles = CloudFileRules.newNotifications(_availableRemoteFiles.value, newState.notifiedVersions)
            // لا نضع «أُشعِر» إذا كان الإذن أو القناة معطلاً أو فشل عرض الإشعار.
            if (notificationHelper.notifyNewRemoteFiles(newFiles)) catalog.update(activeConnection) {
                it.copy(notifiedVersions = it.notifiedVersions + newFiles.map { file -> file.notificationToken })
            }
        }
        _lastScanError.value = if (conflicts.isEmpty()) null else "يوجد تعارض في ${conflicts.size} سجل نصي بين الهاتف والخادم؛ أوقفنا الرفع لحماية النسختين. تنزيل الملفات المختارة ما زال متاحاً."
        if (conflicts.isEmpty()) preferences.recordSyncSuccess(System.currentTimeMillis(), "فُحص الخادم؛ ${_availableRemoteFiles.value.size} ملف متاح للتنزيل الاختياري")
        else preferences.recordSyncFailure(_lastScanError.value.orEmpty())
        return Scan(objects, remote, manifestObject?.etag.orEmpty(), catalog.snapshot(activeConnection), _availableRemoteFiles.value, conflicts)
    }

    private suspend fun boundedScanLocked(settings: CloudSyncSettings, notify: Boolean): Scan = try {
        withTimeout(SCAN_TIMEOUT_MS) { scanLocked(settings, notify) }
    } catch (_: TimeoutCancellationException) { throw CloudScanTimeoutException() }

    private suspend fun pushLightweightMetadata(scan: Scan): Result<Int> = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext Result.failure(CloudBusyException())
        _isSyncing.value = true
        try { cloudAttempt {
            withTimeout(SCAN_TIMEOUT_MS) {
                val settings = preferences.snapshot(); requireConnection(settings)
                authManager.requirePermission(AuthPermission.UPLOAD).getOrThrow()
                authManager.requirePermission(AuthPermission.MODIFY).getOrThrow()
                if (scan.metadataConflicts.isNotEmpty()) throw IOException("تعارض في البيانات النصية؛ لم نستبدل النسختين. رفع الملفات المختارة متاح.")
                val generation = settings.lastLocalChangeAt
                val local = JSONObject(backupRepository.buildCloudManifestJson())
                val root = CloudManifestTools.mergeMetadata(local, scan.remoteManifest, scan.state.metadataBaseline?.let(::JSONObject))
                    .put("files", scan.remoteManifest?.optJSONArray("files") ?: JSONArray())
                    .put("cloudFolders", scan.remoteManifest?.optJSONArray("cloudFolders") ?: JSONArray())
                    .put("storageMode", "selective-v2").put("exportedAt", System.currentTimeMillis())
                val text = root.toString()
                val uploaded = r2Client.uploadText(settings.credentials, CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY, text,
                    ifMatch = scan.manifestEtag.takeIf { it.isNotBlank() }, ifNoneMatch = scan.remoteManifest == null).getOrThrow()
                catalog.update(activeConnection) { it.copy(metadataBaseline = text, metadataEtag = uploaded.etag) }
                preferences.recordUploadSuccess(generation, System.currentTimeMillis(), "تمت مزامنة النصوص فقط؛ الملفات الثقيلة تنتظر اختيارك")
                backupRepository.localItemCount()
            }
        } } catch (_: TimeoutCancellationException) { Result.failure(CloudScanTimeoutException())
        } finally { _isSyncing.value = false; mutex.unlock() }
    }

    private suspend fun needsLightweightUpload(state: CloudCatalogSnapshot): Boolean {
        val local = JSONObject(backupRepository.buildCloudManifestJson())
        return !CloudManifestTools.metadataEqual(local, state.metadataBaseline?.let(::JSONObject)) &&
            (state.metadataBaseline != null || CloudManifestTools.metadataTables.any { (local.optJSONArray(it)?.length() ?: 0) > 0 })
    }

    /** يرفع لقطة الاختيار المحددة فقط؛ folderIds بنية لازمة ولا توسع اختيار الملفات بعد التأكيد. */
    suspend fun uploadSelected(plan: CloudUploadPlan): Result<CloudUploadReport> = operation(CloudTransferKind.UPLOAD) {
        val settings = preferences.snapshot(); requireConnection(settings)
        authManager.verifyActiveSessionWithCloud()
        authManager.requirePermission(AuthPermission.UPLOAD).getOrThrow()
        val scan = boundedScanLocked(settings, false)
        val records = backupRepository.getLocalFileRecords().associateBy { it.id }
        val localFolders = backupRepository.getLocalFolders().associateBy { it.id }
        val folderDescriptions = mutableListOf<RemoteCloudFolder>()
        val folderKeys = mutableMapOf<Long, String>()
        val visiting = mutableSetOf<Long>()
        suspend fun describeFolder(id: Long): String? {
            folderKeys[id]?.let { return it }
            if (!visiting.add(id)) return null
            val local = localFolders[id] ?: return null
            val parent = local.parentId?.let { describeFolder(it) }
            val current = catalog.snapshot(activeConnection)
            val linked = current.folderLinks.firstOrNull { it.localId == id && it.localCreatedAt == local.createdAt }
            // دمج تعليمات.md: المجلد الموجود في السحابة أولى من إنشاء هوية جديدة —
            // (1) مجلد مرتبط ما زال موجوداً على الخادم، وإلا (2) مجلد بنفس الاسم في نفس
            // المستوى حتى لو أُنشئ من جهاز آخر، وإلا (3) مجلد جديد. هكذا تختفي المجلدات
            // المكررة بنفس الاسم: يُدمج المحتوى في المجلد القائم بدل إنشاء ثانٍ بجانبه.
            val linkedFolder = linked?.remoteKey?.let { rk -> scan.state.folders.firstOrNull { it.key == rk } }
            val merged = linkedFolder ?: CloudUploadMergeRules.findExistingFolder(scan.state.folders, local.name, parent)
            val key = merged?.key ?: "folder:${UUID.randomUUID()}"
            folderKeys[id] = key
            // المجلد المدموج يبقى باسمه ومكانه كما هو؛ نشر الوصف بمفتاحه لا يحركه ولا يعيد تسميته.
            folderDescriptions += merged ?: RemoteCloudFolder(key, local.name, parent, local.createdAt)
            val link = CloudFolderLink(id, local.createdAt, key)
            catalog.update(activeConnection) { it.copy(folderLinks = it.folderLinks.filterNot { old -> old.localId == id } + link) }
            return key
        }
        for (id in plan.folderIds) describeFolder(id)
        for (id in plan.fileIds) records[id]?.folderId?.let { describeFolder(it) }
        val objects = scan.objects.associateBy { it.key }.toMutableMap()
        val descriptions = mutableListOf<RemoteCloudFile>()
        val snapshot = JSONObject(backupRepository.buildCloudManifestJson())
        val base = scan.state.metadataBaseline?.let(::JSONObject)
        var published = scan.remoteManifest
        var etag = scan.manifestEtag
        suspend fun publish() {
            // تعارض النصوص لا يوقف إرسال الملفات المستقلة ولا يبرر استبدال النص البعيد.
            val root = if (scan.metadataConflicts.isEmpty()) CloudManifestTools.mergeMetadata(snapshot, published, base)
                else JSONObject(published?.toString() ?: snapshot.toString())
            root.put("files", CloudManifestTools.mergeFiles(published?.optJSONArray("files"), descriptions))
                .put("cloudFolders", CloudFolderTree.mergeFolders(published?.optJSONArray("cloudFolders"), folderDescriptions))
                .put("storageMode", "selective-v2").put("exportedAt", System.currentTimeMillis())
            val text = root.toString()
            val response = r2Client.uploadText(settings.credentials, CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY, text,
                ifMatch = etag.takeIf { it.isNotBlank() }, ifNoneMatch = published == null).getOrThrow()
            published = root; etag = response.etag
            val all = discoverFiles(objects.values.toList(), root)
            val folders = CloudFolderTree.foldersFromManifest(root, all, objects.values.toList())
            catalog.update(activeConnection) { it.copy(files = all, folders = folders,
                metadataBaseline = if (scan.metadataConflicts.isEmpty()) text else it.metadataBaseline,
                metadataEtag = if (scan.metadataConflicts.isEmpty()) etag else "",
                notifiedVersions = it.notifiedVersions + descriptions.map { item -> item.notificationToken }) }
            refreshAvailable(catalog.snapshot(activeConnection))
        }
        _lastUploadReport.value = null
        val ctx = currentCoroutineContext()
        var uploaded = 0; var present = 0
        val failed = mutableListOf<String>()
        val batchBytesTotal = plan.fileIds.sumOf { id ->
            records[id]?.let { record -> File(record.filePath).takeIf { it.isFile }?.length() ?: 0L } ?: 0L
        }
        var bytesCompletedBeforeCurrentFile = 0L
        // نشر المجلدات حتى عندما تكون فارغة، قبل بدء الملفات الكبيرة.
        if (folderDescriptions.isNotEmpty()) publish()
        for ((index, id) in plan.fileIds.withIndex()) {
            val file = records[id]
            if (file == null) { failed += "ملف محلي غير متاح ($id)"; continue }
            val disk = File(file.filePath)
            if (!disk.isFile) { failed += file.name; continue }
            val currentFileBytes = disk.length()
            _transferState.value = CloudTransferState(
                kind = CloudTransferKind.UPLOAD,
                fileName = file.name,
                index = index + 1,
                totalFiles = plan.fileIds.size,
                bytesTotal = currentFileBytes,
                phase = "تحضير بصمة ${file.name}",
                bytesCompletedBeforeCurrentFile = bytesCompletedBeforeCurrentFile,
                batchBytesTotal = batchBytesTotal
            )
            try {
                val length = disk.length(); val modified = disk.lastModified()
                val parentKey = file.folderId?.let { folderKeys[it] }
                val state = catalog.snapshot(activeConnection)
                val previous = state.links.firstOrNull { it.localId == file.id && it.localCreatedAt == file.createdAt }
                val hash = if (previous != null && previous.localSize == length && previous.localModifiedAt == modified) previous.sha256
                    else r2Client.sha256Hex(disk) { ctx.ensureActive() }
                val old = previous?.let { objects[it.remoteKey] }
                // reusable يصبح الرابط نفسه حين تتحقق شروط إعادة الاستخدام — smart cast بدل force-unwrap (بوابة «صفر !!» في تعليمات.md)
                val reusable = previous?.takeIf { it.sha256 == hash &&
                    (old == null || it.remoteVersion.isBlank() || it.remoteVersion == "${old.etag}:${old.size}") }
                // دمج تعليمات.md: ملف موجود مسبقاً في السحابة ببصمته واسمه داخل المجلد الوجهة
                // لا يُرفع مرة أخرى حتى لو رُفع من جهاز آخر (بلا رابط محلي). نبحث في قائمة
                // الفحص وفيما خُطط لهذه الجولة معاً، فالتكرار داخل الجولة الواحدة يُدمج أيضاً.
                // تطابق الاسم شرط إلزامي: إعادة استخدام مفتاح باسم مختلف تعني إعادة تسمية صامتة في البيان.
                // المفتاح يُستخرج من كل فرع بنوعه: الرابط المحلي يحمل مفتاحه، وقاعدة الدمج
                // تعيد ملفاً بعيداً بمفتاحه — لا نمزج النوعين في تعبير واحد (مشتركهما Any).
                val mergedKey = reusable?.remoteKey
                    ?: CloudUploadMergeRules.findExistingFile(scan.state.files + descriptions, file.name, file.extension, hash, length, parentKey)?.remoteKey
                val key = mergedKey ?: CloudFileRules.contentObjectKey(hash, file.extension, UUID.randomUUID().toString())
                var obj = objects[key]
                if (obj == null || obj.size != length) {
                    val reserved = CloudFileLink(file.id, key, "", hash, length, modified, file.createdAt)
                    catalog.update(activeConnection) { it.copy(links = it.links.filterNot { oldLink -> oldLink.localId == id } + reserved) }
                    _transferState.value = _transferState.value.copy(phase = "")
                    val response = r2Client.uploadFile(settings.credentials, key, disk, file.mimeType) { done, total ->
                        _transferState.value = _transferState.value.copy(bytesDone = done, bytesTotal = total)
                    }.getOrThrow()
                    obj = R2ObjectSummary(key, length, response.etag, System.currentTimeMillis()); objects[key] = obj
                    uploaded++
                } else {
                    present++
                    // الملف مطابق وموجود في السحابة؛ لا يوجد نقل بايتات لهذا الملف.
                    _transferState.value = _transferState.value.copy(phase = "", bytesDone = length, bytesTotal = length)
                }
                if (disk.length() != length || disk.lastModified() != modified) throw IOException("تغير الملف أثناء الرفع")
                val descriptor = RemoteCloudFile(key, 0L, file.name, file.extension, length, file.mimeType, file.kind,
                    folderId = null, folderName = file.folderId?.let { localFolders[it]?.name }, createdAt = file.createdAt,
                    etag = obj.etag, sha256 = hash, lastModifiedAt = obj.lastModifiedAt, cloudFolderKey = parentKey)
                descriptions += descriptor
                val link = CloudFileLink(id, key, descriptor.versionToken, hash, length, modified, file.createdAt)
                catalog.update(activeConnection) { it.copy(links = it.links.filterNot { oldLink -> oldLink.localId == id } + link) }
                // لا نحتسب الملف السابق في مقام الدفعة إلا بعد اكتمال رفعه/تجاوزه وحفظ رابط البيان.
                _transferState.value = _transferState.value.copy(bytesDone = length, bytesTotal = length)
                publish()
                bytesCompletedBeforeCurrentFile = (bytesCompletedBeforeCurrentFile + length).coerceAtMost(batchBytesTotal)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (error is CloudConflictException) throw error
                failed += file.name
                if (!checkIsOnline()) { failed += plan.fileIds.drop(index + 1).map { records[it]?.name ?: "ملف غير متاح" }; break }
            }
        }
        val report = CloudUploadReport(uploaded, present, folderDescriptions.size, failed)
        _lastUploadReport.value = report
        if (failed.isEmpty()) preferences.recordSyncSuccess(System.currentTimeMillis(), report.message)
        else preferences.recordSyncFailure(report.message)
        report
    }

    private suspend fun ensureFolderPath(
        key: String?,
        folders: List<RemoteCloudFolder> = _remoteFolders.value
    ): com.unihub.app.data.local.entity.FolderEntity? {
        if (key == null) return null
        var parent: com.unihub.app.data.local.entity.FolderEntity? = null
        for (folder in CloudFolderTree.ancestors(folders, key)) {
            val state = catalog.snapshot(activeConnection)
            val link = state.folderLinks.firstOrNull { it.remoteKey == folder.key }
            parent = backupRepository.ensureDownloadedFolder(folder, parent?.id, link)
            val saved = parent
            val localLink = CloudFolderLink(saved.id, saved.createdAt, folder.key)
            catalog.update(activeConnection) { it.copy(folderLinks = it.folderLinks.filterNot { old -> old.remoteKey == folder.key } + localLink) }
        }
        return parent
    }

    private suspend fun ensureConnection(creds: R2Credentials): CloudCatalogSnapshot {
        val id = connectionId(creds)
        if (id != activeConnection) {
            verificationJob?.cancel(); verificationJob = null
            activeConnection = id
            _verifyingKeys.value = emptySet(); _localVerification.value = CloudLocalVerification()
            _availableRemoteFiles.value = emptyList()
            _allRemoteFiles.value = emptyList(); _remoteFolders.value = emptyList()
            _lastScanError.value = null
        }
        return catalog.snapshot(id)
    }

    private suspend fun localStats(): List<CloudLocalFileStat> = backupRepository.getExistingLocalFiles().map { file ->
        val disk = File(file.filePath)
        CloudLocalFileStat(file.id, file.createdAt, file.filePath, disk.length(), disk.lastModified())
    }

    private suspend fun refreshAvailable(state: CloudCatalogSnapshot, startVerification: Boolean = true) {
        if (state.connectionId != activeConnection) return
        val cachedFiles = state.files.map { it.copy(cloudFolderKey = it.cloudFolderKey ?: it.folderId?.let { id -> "legacy:$id" }) }
        _allRemoteFiles.value = cachedFiles
        _remoteFolders.value = if (state.folders.isNotEmpty()) state.folders else CloudFolderTree.foldersFromManifest(
            state.metadataBaseline?.let { runCatching { JSONObject(it) }.getOrNull() }, cachedFiles, emptyList())
        _localFolders.value = backupRepository.getLocalFolders()
        val stats = localStats()
        val result = CloudPresenceMatcher.compare(cachedFiles, stats, state.localFingerprints, state.links)
        _verifyingKeys.value = result.verifyingKeys
        _availableRemoteFiles.value = cachedFiles.filter { it.remoteKey in result.missingKeys }
        if (result.missingKeys.isEmpty()) notificationHelper.cancel()
        if (startVerification && result.verifyingKeys.isNotEmpty()) startLocalVerification(state.connectionId)
    }

    /** قراءة الملفات تتم خارج قفل HTTP ومهلة الشبكة. كل بصمة مكتملة تحفظ فوراً لإعادة استخدام النتائج. */
    @Synchronized
    private fun startLocalVerification(id: String) {
        if (verificationJob?.isActive == true) return
        verificationJob = scope.launch {
            _localVerification.value = CloudLocalVerification(active = true)
            var completed = 0
            try {
                val state = catalog.snapshot(id)
                val sizes = state.files.filter { CloudPresenceMatcher.strongHash(it.sha256) }.mapTo(mutableSetOf()) { it.size }
                val stats = localStats()
                val validIds = state.localFingerprints.filter { fp -> stats.any { CloudPresenceMatcher.validFingerprint(fp, it) } }.mapTo(mutableSetOf()) { it.localId }
                val todo = stats.filter { it.size in sizes && it.id !in validIds }
                val ctx = currentCoroutineContext()
                for (stat in todo) {
                    ctx.ensureActive()
                    if (activeConnection != id) return@launch
                    val disk = File(stat.path)
                    _localVerification.value = CloudLocalVerification(true, disk.name, completed, todo.size)
                    val hash = r2Client.sha256Hex(disk) { ctx.ensureActive() }
                    val now = localStats().firstOrNull { it.id == stat.id }
                    if (now != null && now == stat) {
                        val fp = CloudLocalFingerprint(stat.id, stat.createdAt, stat.path, stat.size, stat.modifiedAt, hash)
                        catalog.update(id) { it.copy(localFingerprints = it.localFingerprints.filterNot { old -> old.localId == stat.id } + fp) }
                    }
                    completed++
                    refreshAvailable(catalog.snapshot(id), startVerification = false)
                }
                if (activeConnection == id) {
                    refreshAvailable(catalog.snapshot(id), startVerification = false)
                    val settings = preferences.snapshot()
                    val canPull = authManager.currentAuthenticatedUser()?.effectivePermissions?.canDownload == true
                    if (settings.autoSyncEnabled && canPull) {
                        val current = catalog.snapshot(id)
                        val newFiles = CloudFileRules.newNotifications(_availableRemoteFiles.value, current.notifiedVersions)
                        if (notificationHelper.notifyNewRemoteFiles(newFiles)) catalog.update(id) {
                            it.copy(notifiedVersions = it.notifiedVersions + newFiles.map { file -> file.notificationToken })
                        }
                    }
                }
                _localVerification.value = CloudLocalVerification(completed = completed, total = todo.size)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _localVerification.value = CloudLocalVerification(completed = completed, error = "تعذّر التحقق من بعض الملفات المحلية: ${error.message}")
            } finally {
                if (_localVerification.value.active) _localVerification.value = _localVerification.value.copy(active = false)
            }
        }
    }

    private fun discoverFiles(objects: List<R2ObjectSummary>, manifest: JSONObject?): List<RemoteCloudFile> {
        val rows = manifest?.optJSONArray("files") ?: JSONArray()
        val folders = manifest?.optJSONArray("folders") ?: JSONArray()
        val folderNames = (0 until folders.length()).mapNotNull { i ->
            folders.optJSONObject(i)?.let { it.optLong("id") to it.optString("name") }
        }.toMap()
        val cloudFolders = manifest?.optJSONArray("cloudFolders") ?: JSONArray()
        val cloudNames = (0 until cloudFolders.length()).mapNotNull { cloudFolders.optJSONObject(it)?.let(::folderFromJson) }.associate { it.key to it.name }
        val metadata = (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            if (row.optString("remoteKey").isBlank() && row.optLong("id") > 0) {
                row.put("remoteKey", CloudflareR2Config.remoteFileObjectKey(row.optLong("id"), row.optString("extension")))
            }
            remoteFileFromJson(row)
        }.associateBy { it.remoteKey }
        val system = setOf(
            CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY,
            CloudflareR2Config.REMOTE_META_OBJECT_KEY,
            CloudflareR2Config.REMOTE_BACKUP_OBJECT_KEY,
            CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY
        )
        return objects.filterNot { it.key in system || it.key.endsWith('/') || it.key.startsWith("unihub-tests/") }.map { obj ->
            val known = metadata[obj.key]
            if (known != null) known.copy(
                size = obj.size, etag = obj.etag, lastModifiedAt = obj.lastModifiedAt,
                folderName = known.cloudFolderKey?.let { cloudNames[it] } ?: known.folderId?.let { folderNames[it] } ?: known.folderName,
                cloudFolderKey = known.cloudFolderKey ?: known.folderId?.let { "legacy:$it" },
                sha256 = known.sha256.takeIf { known.etag == obj.etag }.orEmpty()
            ) else {
                val fullName = obj.key.substringAfterLast('/')
                val dot = fullName.lastIndexOf('.')
                val extension = if (dot > 0) fullName.substring(dot + 1).lowercase(Locale.US) else ""
                RemoteCloudFile(
                    remoteKey = obj.key, id = 0, name = if (dot > 0) fullName.substring(0, dot) else fullName,
                    extension = extension, size = obj.size,
                    mimeType = URLConnection.guessContentTypeFromName(fullName) ?: mimeType(extension),
                    kind = FileKind.fromExtension(extension), folderName = obj.key.substringBeforeLast('/', "السحابة"),
                    createdAt = obj.lastModifiedAt, etag = obj.etag, lastModifiedAt = obj.lastModifiedAt,
                    cloudFolderKey = obj.key.substringBeforeLast('/', "").takeIf { it.isNotBlank() }?.let { "path:$it" }
                )
            }
        }.sortedWith(compareByDescending<RemoteCloudFile> { it.lastModifiedAt }.thenBy { it.fullDisplayName })
    }

    private fun mimeType(extension: String): String = when (extension) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private suspend fun <T> operation(kind: CloudTransferKind, block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext Result.failure(CloudBusyException())
        _isSyncing.value = true
        _transferState.value = CloudTransferState(kind = kind, phase = "تجهيز ${if (kind == CloudTransferKind.UPLOAD) "الرفع" else "التنزيل"}…")
        downloadJob = currentCoroutineContext().job
        if (!stagingCleaned) {
            // الأجزاء الصالحة تُترك ليُستأنف منها؛ يُمحى ما لم يعد له معنى (قديم أو بغير صيغتنا)
            val staging = File(context.filesDir, "cloud-downloads")
            val now = System.currentTimeMillis()
            staging.listFiles()?.forEach { file ->
                if (!file.name.endsWith(CloudDownloadPart.SUFFIX) || CloudDownloadPart.isStale(now, file.lastModified())) file.delete()
            }
            stagingCleaned = true
        }
        try {
            cloudAttempt(block).onFailure { error -> cloudAttempt { preferences.recordSyncFailure(CloudFailureMessages.userMessage(error)) } }
        } finally {
            downloadJob = null; _isSyncing.value = false
            _transferState.value = CloudTransferState(); mutex.unlock()
        }
    }

    private fun requireConnection(settings: CloudSyncSettings) {
        if (!settings.isConfigured) throw IOException("بيانات خادم R2 غير مضبوطة")
        if (!checkIsOnline()) throw IOException("لا يتوفر إنترنت؛ البيانات والملفات السابقة محفوظة محلياً")
    }

    fun checkIsOnline(): Boolean {
        val online = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
        _isOnline.value = online
        return online
    }

    private fun connectionId(creds: R2Credentials): String = MessageDigest.getInstance("SHA-256")
        .digest("${creds.resolvedEndpoint}/${creds.bucketName}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }

    /**
     * المساحة المتاحة فعلياً لاستقبال بيانات جديدة في وحدة التخزين الداخلية.
     *
     * يُفضَّل StorageManager#getAllocatableBytes (متوفر من API 26 = أدنى SDK
     * ندعمه) لأنه الواجهة التي توصي بها Google لقرار «هل تكفي المساحة لبيانات
     * جديدة؟»: يحسب أيضاً البيانات المخبّأة التي يستطيع النظام مسحها لحسابنا،
     * فلا يرفض التنزيل زوراً بينما توجد غيغابايتات من الكاش القابل للمسح.
     * يعيد قيمة ≥ usableSpace دائماً، لذلك لا تفقد الفحوص حذرها: يبقى هامش
     * الأمان RESERVE_BYTES مطروحاً في مواضع النداء، والكتابة الفعلية نفسها
     * تفشل بـ IOException إن سبقنا مستهلك آخر للمساحة.
     *
     * المسار الاحتياطي usableSpace (التقدير المحافظ) محجوز لحالة تعذّر الوصول
     * إلى خدمة التخزين أو رفضها الاستعلام، لذا نكبح تحذير lint هنا مبرراً.
     */
    @SuppressLint("UsableSpace")
    private fun freeSpaceForNewData(): Long {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return context.filesDir.usableSpace
        return try {
            storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
        } catch (e: IOException) {
            context.filesDir.usableSpace
        }
    }

    companion object {
        private const val TAG = "CloudSyncManager"
        private const val LIVE_PULSE_INTERVAL_MS = 20_000L
        private const val EMPTY_PULSE_INTERVAL_MS = 120_000L
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val METADATA_EDIT_TIMEOUT_MS = 60_000L
        private const val RESERVE_BYTES = 16L * 1024 * 1024
    }
}
