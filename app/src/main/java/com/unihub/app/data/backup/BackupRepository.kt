package com.unihub.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.unihub.app.core.common.DateFormats
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.data.cloud.CloudflareR2Config
import com.unihub.app.data.cloud.CloudManifestTools
import com.unihub.app.data.cloud.RemoteCloudFile
import com.unihub.app.data.cloud.RemoteCloudFolder
import com.unihub.app.data.cloud.CloudFolderLink
import com.unihub.app.data.cloud.CloudDownloadPlacement
import com.unihub.app.data.cloud.CloudUploadMergeRules
import com.unihub.app.data.local.UniHubDatabase
import com.unihub.app.data.local.entity.ExamEntity
import com.unihub.app.data.local.entity.ExamType
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.local.entity.LectureEntity
import com.unihub.app.data.local.entity.NoteEntity
import com.unihub.app.data.local.entity.TaskEntity
import com.unihub.app.data.local.entity.TaskPriority
import com.unihub.app.data.local.entity.Weekday
import com.unihub.app.data.storage.FileStorage
import com.unihub.app.notifications.ReminderScheduler
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * النسخ الاحتياطي (تصدير/استيراد).
 *
 * إصلاح جوهري في هذه الجولة (راجع تعليمات.md — "فقدان الملفات في النسخ
 * الاحتياطية"): كانت النسخة تُصدَّر كملف JSON للبيانات الوصفية فقط، فلا تحتوي
 * على محتوى الملفات الفعلي إطلاقاً — عند استعادتها على جهاز آخر (أو بعد إعادة
 * تثبيت التطبيق) تبقى السجلات موجودة لكن بلا أي ملف فعلي يقابلها. الآن يُصدَّر
 * أرشيف ZIP يحوي:
 *   - manifest.json  : نفس البيانات الوصفية كما كانت بالضبط (متوافقة مع القديم)
 *   - files/<id>.<ext>: محتوى كل ملف موجود فعلياً وقت التصدير
 * وعند الاستيراد تُعاد كتابة محتوى كل ملف داخل تخزين هذا الجهاز نفسه ويُربط
 * مساره الجديد بالسجل — بدل الاعتماد على مسار مطلق قد لا يوجد على هذا الجهاز.
 *
 * التوافق العكسي: نُسخ JSON القديمة (بلا أرشفة فعلية) لا تزال تُقرأ بنجاح —
 * تُستعاد بياناتها الوصفية فقط تماماً كسلوكها الأصلي (لا تراجع في الميزات).
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: UniHubDatabase,
    private val reminderScheduler: ReminderScheduler,
    private val fileStorage: FileStorage
) {

    companion object {
        const val SCHEMA_VERSION = 3
        private const val TAG = "BackupRepository"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val FILES_PREFIX = "files/"

        /** حد أقصى لحجم أرشيف النسخة الاحتياطية كاملاً أثناء الاستيراد */
        private const val MAX_BACKUP_BYTES = 1_500L * 1024 * 1024
        // Legacy JSON contains metadata only; a 1.5 GB limit here could itself trigger OOM.
        private const val MAX_LEGACY_JSON_BYTES = 32L * 1024 * 1024
        private const val MAX_CLOUD_MANIFEST_BYTES = 16L * 1024 * 1024

        /**
         * النسخ الاحتياطية قد تحتوي على ملف سحابي أكبر من حد الاستيراد العادي (200 MB).
         * المحتوى يُنسخ بتدفّق ثابت إلى القرص، لذلك نسمح حتى 1 GB لكل عنصر مع حد إجمالي 1.5 GB.
         */
        private const val MAX_BACKUP_ENTRY_BYTES = 1_500L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024
        /** حد أقصى لعدد عناصر ZIP لمنع ملفات صغيرة كثيرة من استنزاف القرص والوقت. */
        private const val MAX_ARCHIVE_ENTRIES = 20_000
        /** حد أقصى لمجموع السجلات داخل JSON؛ حماية إضافية من قوائم بيانات هائلة. */
        private const val MAX_MANIFEST_RECORDS = 50_000

        /** وسيط القراءة من مزوّد الـURI — يمرّر الأرشيف بحمل ذاكرة ثابت صغير */
        private const val READ_BUFFER_BYTES = 32 * 1024

        /**
         * مجلد مؤقت داخل تخزين التطبيق تُلغى فيه عناصر الأرشيف قبل الاستعادة.
         * داخل filesDir عمدًا: [FileStorage.importTempFile] ينقله نقلاً (rename) بلا نسخ،
         * و FileStorage.clearAll() يحذف مكتبة المستخدم فقط فلا يمسّه.
         */
        private const val IMPORT_SPOOL_PREFIX = "backup_import_"

        /** رسالة موحّدة لأي عطل غير متوقع — نص الاستثناء التقني لا يفيد المستخدم */
        private const val FRIENDLY_IMPORT_FAILURE =
            "تعذّر استيراد النسخة الاحتياطية — تأكد أنها ملف نسخة كامل صادر من هذا التطبيق"

        private fun fileEntryName(id: Long, extension: String): String {
            // ZIP paths are untrusted input on restore and must remain a single safe leaf.
            val safeExtension = extension.trim().trimStart('.')
                .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                .take(16)
                .ifBlank { "bin" }
            return "$FILES_PREFIX$id.$safeExtension"
        }
    }

    /**
     * حارس ذري يشير إلى أن القاعدة تُحدَّث حالياً بسبب سحب/استيراد نسخة،
     * فيتجاهل [AutoBackupChangeWatcher] هذا التحديث ولا يعيد رفعه في حلقة مفرغة.
     * ضمان الترتيب يأتي من [finishRemoteApply]: يسلّم إشعارات Room كاملةً قبل
     * إنهاء الحارس، فلا حاجة لطابع زمني لامتصاص إشعارات متأخرة.
     */
    val isApplyingRemoteSync = AtomicBoolean(false)

    fun shouldIgnoreInvalidation(): Boolean = isApplyingRemoteSync.get()

    // refreshVersionsSync تُسلّم إشعارات المعاملة على الخيط الحالي قبل أن تُعيد
    // التحكم، بخلاف refreshVersionsAsync التي تجدولها على queryExecutor وتعود
    // فوراً — ولأن [AutoBackupChangeWatcher] يقرأ الحارس لحظة وصول الإشعار، فالنسخة
    // المتزامنة هي الوحيدة التي تضمن التسليم داخل نافذة الحارس (منع حلقة الرفع).
    // الواجهة مقيّدة بـ LIBRARY_GROUP_PREFIX في Room 2.6.1 فنتجاوز تحذير lint
    // عمداً وتوثيقاً — الاستدعاء داخل تطبيق واحد والسلوك مقصود ومُختبَر بالدلالة
    // (فحص المصدر: المتزامنة تُنفّذ refreshRunnable مباشرة، وغير المتزامنة تجدوله).
    @SuppressLint("RestrictedApi")
    private fun finishRemoteApply() {
        try {
            database.invalidationTracker.refreshVersionsSync()
        } catch (error: Exception) {
            // The Room transaction may already be committed; invalidation delivery must
            // never make the caller delete newly imported files as if restore had failed.
            android.util.Log.w(TAG, "تعذّر تحديث مراقبة تغييرات قاعدة البيانات بعد المزامنة", error)
        } finally {
            isApplyingRemoteSync.set(false)
        }
    }

    /** حساب إجمالي العناصر المحفوظة محلياً حالياً في جميع الجداول */
    suspend fun localItemCount(): Int = withContext(Dispatchers.IO) {
        database.folderDao().getAllOnce().size +
            database.fileDao().getAllOnce().size +
            database.taskDao().getAllOnce().size +
            database.noteDao().getAllOnce().size +
            database.examDao().getAllOnce().size +
            database.lectureDao().getAllOnce().size
    }

    /** إرجاع السجلات التي تشير إلى ملفات موجودة داخل مكتبة التطبيق فقط. */
    suspend fun getExistingLocalFiles(): List<FileEntity> = withContext(Dispatchers.IO) {
        database.fileDao().getAllOnce().filter { fileStorage.isManagedFilePath(it.filePath) }
    }

    /** حارس المسارات لكل مستهلك لاحق (ومنها رفع السحابة). */
    fun isManagedLocalFile(path: String): Boolean = fileStorage.isManagedFilePath(path)

    suspend fun getLocalFileRecords(): List<FileEntity> = withContext(Dispatchers.IO) { database.fileDao().getAllOnce() }
    suspend fun getLocalFolders(): List<FolderEntity> = withContext(Dispatchers.IO) { database.folderDao().getAllOnce() }

    /** إعادة استخدام مجلد سحابي محلي مطابق قبل إنشاء صف جديد، دون حذف أو إعادة تسمية مجلد المستخدم. */
    suspend fun ensureDownloadedFolder(remote: RemoteCloudFolder, parentId: Long?, link: CloudFolderLink?): FolderEntity =
        withContext(Dispatchers.IO) {
            isApplyingRemoteSync.set(true)
            try {
                database.withTransaction {
                    val dao = database.folderDao()
                    val allFolders = dao.getAllOnce()
                    val existing = CloudDownloadPlacement.resolveLocalFolder(allFolders, remote, parentId, link)
                    if (existing != null) existing else {
                        val validParent = parentId?.takeIf { pid -> allFolders.any { it.id == pid } }
                        val safeName = InputValidator.sanitizeName(remote.name).ifBlank { "مجلد سحابي" }
                        val row = FolderEntity(
                            name = safeName,
                            parentId = validParent,
                            createdAt = remote.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                        )
                        row.copy(id = dao.insert(row))
                    }
                }
            } finally { finishRemoteApply() }
        }

    /** إرجاع خريطة بأسماء المجلدات المحلية (معرّف المجلد -> اسمه) */
    suspend fun getFolderNamesMap(): Map<Long, String> = withContext(Dispatchers.IO) {
        database.folderDao().getAllOnce().associate { it.id to it.name }
    }

    /**
     * بناء نص الفهرس السحابي (`unihub_manifest.json`) شاملاً الجداول الخفيفة
     * وقائمة الملفات مع مفاتيحها المستقلة على الخادم وأحجامها.
     */
    suspend fun buildCloudManifestJson(): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            val fileRecords = database.fileDao().getAllOnce()
            val manifest = JSONObject().apply {
                put("app", "unihub")
                put("schemaVersion", SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("folders", exportFolders())
                put("files", exportFiles(fileRecords.filter { fileStorage.isManagedFilePath(it.filePath) }))
                put("tasks", exportTasks())
                put("notes", exportNotes())
                put("exams", exportExams())
                put("lectures", exportLectures())
            }
            val count = totalCount(manifest)
            if (count > MAX_MANIFEST_RECORDS) {
                throw IOException("عدد سجلات الجامعة يتجاوز الحد الآمن للمزامنة")
            }
            val text = manifest.toString()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_CLOUD_MANIFEST_BYTES) {
                throw IOException("بيانات الجامعة كبيرة جدًا للمزامنة؛ قسّم المحتوى أو احذف السجلات القديمة")
            }
            text
        }
    }

    /**
     * مزامنة البيانات النصية الخفيفة فقط (المجلدات، المهام، الملاحظات، الامتحانات، الجدول)
     * من الفهرس السحابي دون تنزيل الملفات الثقيلة ودون حذف الملفات المحلية الموجودة على الهاتف.
     */
    suspend fun syncLightweightMetadataFromManifest(
        manifestText: String,
        expectedLocalMetadata: String? = null
    ): Result<List<RemoteCloudFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = parseAndValidateManifest(manifestText)
                val folders = orderFoldersForInsertion(parseFolders(root.optJSONArray("folders")))
                val tasks = parseTasks(root.optJSONArray("tasks"))
                val notes = parseNotes(root.optJSONArray("notes"))
                val exams = parseExams(root.optJSONArray("exams"))
                val lectures = parseLectures(root.optJSONArray("lectures"))
                isApplyingRemoteSync.set(true)
                try {
                    database.withTransaction {
                        if (expectedLocalMetadata != null) {
                            // الفحص والكتابة تحت نفس قفل Room، فلا نمسح تعديلاً وقع بعد لقطة الشبكة.
                            val current = JSONObject().apply {
                                put("folders", exportFolders())
                                put("tasks", exportTasks())
                                put("notes", exportNotes())
                                put("exams", exportExams())
                                put("lectures", exportLectures())
                            }
                            CloudManifestTools.requireUnchangedLocalMetadata(JSONObject(expectedLocalMetadata), current)
                        }
                        val dao = database.folderDao()
                        val current = dao.getAllOnce().associateBy { it.id }
                        folders.forEach { folder ->
                            if (folder.id !in current) dao.insert(folder)
                            else if (current[folder.id] != folder) dao.update(folder)
                        }
                        // هذه البيانات سبق دمجها ثلاثياً قبل الوصول إلى هنا.
                        // لا نمسح جدول الملفات أو المجلدات، فلا يقع CASCADE على المحتوى المحلي.
                        val oldTasks = database.taskDao().getAllOnce().associateBy { it.id }
                        oldTasks.values.filter { old -> tasks.none { it.id == old.id } }
                            .forEach { database.taskDao().delete(it) }
                        tasks.forEach { if (it.id !in oldTasks) database.taskDao().insert(it)
                            else if (oldTasks[it.id] != it) database.taskDao().update(it) }
                        val oldNotes = database.noteDao().getAllOnce().associateBy { it.id }
                        oldNotes.values.filter { old -> notes.none { it.id == old.id } }
                            .forEach { database.noteDao().delete(it) }
                        notes.forEach { if (it.id !in oldNotes) database.noteDao().insert(it)
                            else if (oldNotes[it.id] != it) database.noteDao().update(it) }
                        val oldExams = database.examDao().getAllOnce().associateBy { it.id }
                        oldExams.values.filter { old -> exams.none { it.id == old.id } }
                            .forEach { database.examDao().delete(it) }
                        exams.forEach { if (it.id !in oldExams) database.examDao().insert(it)
                            else if (oldExams[it.id] != it) database.examDao().update(it) }
                        val oldLectures = database.lectureDao().getAllOnce().associateBy { it.id }
                        oldLectures.values.filter { old -> lectures.none { it.id == old.id } }
                            .forEach { database.lectureDao().delete(it) }
                        lectures.forEach { if (it.id !in oldLectures) database.lectureDao().insert(it)
                            else if (oldLectures[it.id] != it) database.lectureDao().update(it) }
                    }
                } finally {
                    finishRemoteApply()
                }
                reminderScheduler.cancelAll()
                exams.filter { it.date >= DateFormats.todayIso() }
                    .forEach(reminderScheduler::scheduleExamReminders)
                tasks.filter { !it.isDone && !it.dueDate.isNullOrBlank() }
                    .forEach(reminderScheduler::scheduleTaskReminder)
                parseRemoteCloudFiles(root.optJSONArray("files"), getFolderNamesMap())
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }

    /** يحفظ ملفاً مختاراً؛ لا نستعمل id البعيد لتحديد سجل Room المحلي. */
    suspend fun saveSelectedRemoteFile(
        remoteFile: RemoteCloudFile,
        tempDownloadedFile: File,
        targetLocalId: Long? = null
    ): Result<FileEntity> = withContext(Dispatchers.IO) {
        var newPath: String? = null
        var committed = false
        runCatching {
            val path = fileStorage.importTempFile(tempDownloadedFile, remoteFile.name, remoteFile.extension)
            newPath = path
            var oldPath: String? = null
            isApplyingRemoteSync.set(true)
            val saved = try {
                database.withTransaction {
                    val validFolder = remoteFile.folderId?.takeIf { id ->
                        val folder = database.folderDao().getById(id)
                        folder != null && (
                            remoteFile.folderName == null ||
                                CloudUploadMergeRules.sameName(
                                    InputValidator.sanitizeName(folder.name),
                                    InputValidator.sanitizeName(remoteFile.folderName)
                                )
                            )
                    }
                    val existing = targetLocalId?.let { id -> database.fileDao().getAllOnce().firstOrNull { it.id == id } }
                    val row = FileEntity(
                        id = existing?.id ?: 0L,
                        name = remoteFile.name.ifBlank { "ملف سحابي" },
                        extension = remoteFile.extension,
                        kind = remoteFile.kind,
                        mimeType = remoteFile.mimeType,
                        size = File(path).length(),
                        folderId = validFolder,
                        filePath = path,
                        isFavorite = existing?.isFavorite ?: false,
                        createdAt = remoteFile.createdAt
                    )
                    val result = if (existing != null) {
                        database.fileDao().update(row)
                        oldPath = existing.filePath
                        row
                    } else {
                        row.copy(id = database.fileDao().insert(row))
                    }
                    result
                }.also { committed = true }
            } finally {
                finishRemoteApply()
            }
            oldPath?.takeIf { it != path }?.let(fileStorage::delete)
            saved
        }.onFailure { error ->
            if (!committed) newPath?.let(fileStorage::delete)
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    private fun parseRemoteCloudFiles(
        array: JSONArray?,
        folderNamesById: Map<Long, String>
    ): List<RemoteCloudFile> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id", 0L)
            val name = obj.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val ext = obj.optString("extension").trim()
            val remoteKey = obj.optString("remoteKey").takeIf { it.isNotBlank() }
                ?: CloudflareR2Config.remoteFileObjectKey(id, ext)
            val folderId = if (obj.isNull("folderId")) null else obj.optLong("folderId")
            RemoteCloudFile(
                remoteKey = remoteKey,
                id = id,
                name = name,
                extension = ext,
                size = obj.optLong("size", 0L),
                mimeType = obj.optString("mimeType", "application/octet-stream"),
                kind = FileKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: FileKind.OTHER,
                folderId = folderId,
                folderName = folderId?.let { folderNamesById[it] },
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                etag = obj.optString("etag"),
                sha256 = obj.optString("sha256"),
                lastModifiedAt = obj.optLong("lastModifiedAt")
            )
        }
    }

    // ====== التصدير ======

    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { exportToStream(it) }
                ?: throw IOException("تعذّر فتح ملف الوجهة للكتابة")
        }.onFailure { android.util.Log.e(TAG, "فشل التصدير", it) }
    }

    /**
     * الكتابة إلى تدفق خارجي — مشتركة بين التصدير اليدوي (اختيار ملف عبر SAF)
     * والنسخ الاحتياطي التلقائي إلى مجلد المستخدم (انظر AutoBackupExporter).
     * يجب استدعاؤها من خيط إدخال/إخراج.
     *
     * يُكتب سطر الشفرة [BackupSignature] في الرأس قبل أرشيف ZIP — بهذه الشفرة
     * يتعرّف التطبيق على ملف النسخة إذا شُورك إليه ويعرض حوار الاستيراد.
     */
    suspend fun exportToStream(rawOut: java.io.OutputStream): Int {
        // Capture all tables in one Room transaction: metadata and file references must
        // describe one consistent point in time, not a mixture of concurrent edits.
        val (manifest, fileRecords) = database.withTransaction {
            val allFileRecords = database.fileDao().getAllOnce()
            val safeFiles = allFileRecords.filter { fileStorage.isManagedFilePath(it.filePath) }
            if (safeFiles.size != allFileRecords.size) {
                android.util.Log.w(TAG, "تم استبعاد ${allFileRecords.size - safeFiles.size} سجل ملف بمسار مفقود أو خارج مكتبة التطبيق من النسخة الاحتياطية")
            }
            val snapshot = JSONObject().apply {
                put("app", "unihub")
                put("schemaVersion", SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("folders", exportFolders())
                put("files", exportFiles(safeFiles))
                put("tasks", exportTasks())
                put("notes", exportNotes())
                put("exams", exportExams())
                put("lectures", exportLectures())
            }
            snapshot to safeFiles
        }

        val recordCount = totalCount(manifest)
        if (recordCount > MAX_MANIFEST_RECORDS) {
            throw IOException("عدد السجلات أكبر من الحد الآمن لإنشاء النسخة الاحتياطية")
        }
        if (fileRecords.size + 1 > MAX_ARCHIVE_ENTRIES) {
            throw IOException("عدد الملفات أكبر من الحد المسموح في النسخة الاحتياطية؛ قسّم المكتبة أولاً")
        }
        val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
        if (manifestBytes.size.toLong() > MAX_MANIFEST_BYTES) {
            throw IOException("بيانات النسخة الاحتياطية كبيرة جدًا؛ قسّم المكتبة أو احذف السجلات القديمة")
        }
        var expandedBytes = manifestBytes.size.toLong()
        if (expandedBytes > MAX_BACKUP_BYTES) {
            throw IOException("حجم النسخة الاحتياطية يتجاوز الحد الآمن")
        }

        // 0) شفرة التعريف في بداية الملف (سطر نصي قبل تدفق ZIP)
        rawOut.write(BackupSignature.HEADER_BYTES)
        rawOut.flush()

        ZipOutputStream(rawOut).use { zip ->
            // 1) بيانات وصفية
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestBytes)
            zip.closeEntry()

            // 2) Stream file bytes from disk with per-entry and total uncompressed limits.
            fileRecords.forEach { file ->
                val disk = File(file.filePath)
                if (!fileStorage.isManagedFilePath(disk.path) || !disk.isFile) {
                    throw IOException("الملف «${file.name}» لم يعد موجودًا داخل مكتبة التطبيق؛ أُوقِف التصدير لتجنب نسخة ناقصة")
                }
                if (disk.length() > MAX_BACKUP_ENTRY_BYTES) {
                    throw IOException("الملف «${file.name}» أكبر من الحد المسموح لعنصر النسخ الاحتياطي (1.5 ج.ب)")
                }
                zip.putNextEntry(ZipEntry(fileEntryName(file.id, file.extension)))
                disk.inputStream().buffered(READ_BUFFER_BYTES).use { input ->
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    var entryBytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (entryBytes > MAX_BACKUP_ENTRY_BYTES - n.toLong() ||
                            expandedBytes > MAX_BACKUP_BYTES - n.toLong()) {
                            throw IOException("حجم محتوى النسخة الاحتياطية تجاوز الحد الآمن أثناء التصدير")
                        }
                        zip.write(buffer, 0, n)
                        entryBytes += n
                        expandedBytes += n
                    }
                }
                zip.closeEntry()
            }
        }

        return recordCount
    }

    // ====== الاستيراد ======

    /**
     * يستورد نسخة احتياطية من [uri] بلا تحميلها في الذاكرة: يُقرأ التدفق قطعاً
     * ثابتة، ويُنزل محتوى كل ملف مضمّن إلى قرص مؤقت حتى لحظة الاستعادة.
     *
     * المسار القديم كان يقرأ الملف كاملاً بـ readBytes() ثم ينسخه مرة لإسقاط سطر
     * الشفرة، ثم يجمع كل الملفات المضمّنة في HashMap من ByteArray — نسخة واحدة فيها
     * محاضرة مرئية كانت تكفي لامتلاء كومة الـ Dalvik/ART وفشل الاستيراد برسالة
     * «Failed to allocate a … byte allocation … until OOM».
     */
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { importFrom(uri) }
            .recoverCatching { error ->
                // المستخدم يرى رسالة عربية مختصرة، والسجل يحتفظ بالتفاصيل التقنية كاملة
                android.util.Log.e(TAG, "فشل الاستيراد", error)
                throw error as? IOException ?: IOException(FRIENDLY_IMPORT_FAILURE)
            }
    }

    private suspend fun importFrom(uri: Uri): Int {
        // حدّ الحجم يُقارن قبل القراءة: محاولة قراءة ملف ضخم هي نفسها ما كان يُسقط التطبيق.
        // بعض المزوّدين لا تُبلغ عن الحجم (-1) فيُترك الأرشيف يُعالَج والحماية لكل عنصر قائمة.
        val declaredSize = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        if (declaredSize > MAX_BACKUP_BYTES) {
            throw IOException("ملف النسخة الاحتياطية أكبر من الحد المسموح")
        }

        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("تعذّر قراءة ملف النسخة الاحتياطية")
        val spoolDir = File(context.filesDir, "$IMPORT_SPOOL_PREFIX${UUID.randomUUID()}")

        return source.buffered(READ_BUFFER_BYTES).use { buffered ->
            try {
                // النسخ الجديدة تبدأ بسطر الشفرة، والقديمة تبدأ بـ PK مباشرة أو بـ { لملف JSON
                BackupStream.skipSignatureLine(buffered, BackupSignature.HEADER_BYTES)
                if (BackupStream.looksLikeZip(buffered)) {
                    importFromZip(buffered, spoolDir)
                } else {
                    importFromLegacyJson(buffered)
                }
            } finally {
                // يمسح المؤقت في كل الحالات: نجاحاً كان أم فشلاً جزئياً بعد clearAll()
                runCatching { spoolDir.deleteRecursively() }
            }
        }
    }

    /** مسار التوافق العكسي: نسخة JSON قديمة (بيانات وصفية فقط) — نص واحد لا ثلاث نسخ منه */
    private suspend fun importFromLegacyJson(source: InputStream): Int {
        val text = BackupStream.readLegacyTextBounded(source, MAX_LEGACY_JSON_BYTES)
        val root = parseAndValidateManifest(text)
        // Legacy JSON contains only metadata and untrusted absolute paths. Never restore
        // paths outside this app's private library: cloud sync must not read arbitrary files.
        val files = parseFiles(root.optJSONArray("files"))
            .filter { fileStorage.isManagedFilePath(it.filePath) }
        return applyParsedBackup(root, files)
    }

    /** مسار الأرشيف الجديد: يعيد إنشاء نسخة فعلية من كل ملف مضمّن داخل الأرشيف */
    private suspend fun importFromZip(source: InputStream, spoolDir: File): Int {
        val archive = BackupStream.spool(
            input = source,
            manifestEntry = MANIFEST_ENTRY,
            filesPrefix = FILES_PREFIX,
            spoolDir = spoolDir,
            maxEntryBytes = MAX_BACKUP_ENTRY_BYTES,
            maxTotalBytes = MAX_BACKUP_BYTES,
            maxEntries = MAX_ARCHIVE_ENTRIES
        )

        val text = archive.manifestText ?: throw IOException("النسخة الاحتياطية لا تحتوي على بيانات صالحة")
        val root = parseAndValidateManifest(text)
        val declaredFiles = parseFiles(root.optJSONArray("files"))

        // أضف الملفات المستعادة بجانب المكتبة الحالية أولاً. لا تُحذف النسخة الحالية
        // إلا بعد نجاح معاملة قاعدة البيانات؛ بذلك لا تؤدي نسخة تالفة أو مساحة ممتلئة
        // إلى فقد الملفات الحالية قبل التأكد من اكتمال الاستعادة.
        val newlyImportedPaths = mutableListOf<String>()
        try {
            val restoredFiles = declaredFiles.mapNotNull { fe ->
                val spooled = archive.filesByEntry[fileEntryName(fe.id, fe.extension)]
                    ?.takeIf { it.isFile }
                    ?: throw IOException("النسخة الاحتياطية غير مكتملة: محتوى الملف «${fe.name}» غير موجود")
                val newPath = try {
                    fileStorage.importTempFile(spooled, fe.name, fe.extension)
                } catch (error: Exception) {
                    android.util.Log.e(TAG, "تعذّرت استعادة الملف الفعلي #${fe.id}", error)
                    throw IOException("تعذّرت استعادة الملف «${fe.name}»؛ احتفظت بالمكتبة الحالية", error)
                }
                newlyImportedPaths += newPath
                // The archive manifest is untrusted: physical byte length wins over its declared size.
                fe.copy(filePath = newPath, size = File(newPath).length())
            }

            val restoredCount = applyParsedBackup(root, restoredFiles)
            // نجحت المعاملة وأصبحت المسارات الجديدة هي الحقيقة في Room؛ الآن فقط
            // نحذف الملفات القديمة/اليتيمة. الملفات المستوردة محفوظة بقائمة الحماية.
            val committedPaths = getExistingLocalFiles().mapTo(mutableSetOf()) { it.filePath }
            fileStorage.removeUnreferencedFiles(committedPaths)
            return restoredCount
        } catch (error: Exception) {
            // قبل نجاح المعاملة، احذف فقط ما أضفناه في هذه المحاولة واترك المكتبة القديمة سليمة.
            newlyImportedPaths.forEach(fileStorage::delete)
            throw error
        }
    }

    /** تحقّق موحّد من أن النص نسخة احتياطية صالحة بإصدار مدعوم — مشترك بين المسارين */
    private fun parseAndValidateManifest(text: String): JSONObject {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("الملف ليس نسخة احتياطية صالحة")
        }
        if (root.optString("app") != "unihub") {
            throw IOException("الملف ليس نسخة احتياطية من هذا التطبيق")
        }
        val version = root.optInt("schemaVersion", 1)
        if (version !in 1..SCHEMA_VERSION) {
            throw IOException("إصدار النسخة ($version) غير مدعوم؛ الإصدار المقبول من 1 إلى $SCHEMA_VERSION")
        }
        val recordCount = totalCount(root)
        if (recordCount > MAX_MANIFEST_RECORDS) {
            throw IOException("النسخة تحتوي على عدد سجلات يتجاوز الحد الآمن")
        }
        return root
    }

    /**
     * يطبّق بيانات مُحلَّلة بالفعل (مشتركة بين مسار JSON القديم ومسار ZIP
     * الجديد): يرتب المجلدات، يستبدل كل البيانات ضمن معاملة واحدة، ثم يعيد
     * جدولة كل التذكيرات من الصفر.
     */
    private suspend fun applyParsedBackup(root: JSONObject, files: List<FileEntity>): Int {
        val folders = parseFolders(root.optJSONArray("folders"))
        val tasks = parseTasks(root.optJSONArray("tasks"))
        val notes = parseNotes(root.optJSONArray("notes"))
        val exams = parseExams(root.optJSONArray("exams"))
        val lectures = parseLectures(root.optJSONArray("lectures"))

        // ترتيب آمن للمفاتيح الأجنبية: الآباء قبل الأبناء، وإسقاط السجلات
        // اليتيمة التي تشير إلى أب غير موجود في النسخة (كانت تُفشل المعاملة كلها)
        val orderedFolders = orderFoldersForInsertion(folders)
        val validFolderIds = orderedFolders.mapTo(mutableSetOf()) { it.id }
        val validFiles = files.filter { it.folderId == null || it.folderId in validFolderIds }

        isApplyingRemoteSync.set(true)
        try {
            database.withTransaction {
                val folderDao = database.folderDao()
                val fileDao = database.fileDao()

                folderDao.deleteAll() // يحذف الملفات تبعاً عبر CASCADE
                database.taskDao().deleteAll()
                database.noteDao().deleteAll()
                database.examDao().deleteAll()
                database.lectureDao().deleteAll()

                orderedFolders.forEach { folderDao.insert(it) }
                validFiles.forEach { fileDao.insert(it) }
                tasks.forEach { database.taskDao().insert(it) }
                notes.forEach { database.noteDao().insert(it) }
                exams.forEach { database.examDao().insert(it) }
                lectures.forEach { database.lectureDao().insert(it) }
            }
        } finally {
            finishRemoteApply()
        }

        // قاعدة البيانات والملفات أُكملت استعادتها بالفعل؛ فشل جدولة تذكير لا يجوز
        // أن يُعامل كفشل للاستعادة ولا يؤدي إلى حذف الملفات المشار إليها في Room.
        runCatching { reminderScheduler.cancelAll() }
            .onFailure { android.util.Log.w(TAG, "تعذّر إلغاء التذكيرات القديمة بعد الاستعادة", it) }
        val todayIso = DateFormats.todayIso()
        exams.filter { it.date >= todayIso }.forEach { exam ->
            runCatching { reminderScheduler.scheduleExamReminders(exam) }
                .onFailure { android.util.Log.w(TAG, "تعذّرت جدولة تذكير امتحان بعد الاستعادة", it) }
        }
        tasks.filter { !it.isDone && !it.dueDate.isNullOrBlank() }.forEach { task ->
            runCatching { reminderScheduler.scheduleTaskReminder(task) }
                .onFailure { android.util.Log.w(TAG, "تعذّرت جدولة تذكير مهمة بعد الاستعادة", it) }
        }

        return orderedFolders.size + validFiles.size + tasks.size + notes.size + exams.size + lectures.size
    }

    /**
     * يرتب المجلدات بحيث يُدرج كل أب قبل أبنائه (المفتاح الأجنبي الذاتي على
     * `parentId`). بدون هذا الترتيب قد يصل الابن قبل أبيه فترفض SQLite الإدراج
     * وتفشل عملية الاستيراد كلها. المجلد الذي يشير إلى أب غير موجود في النسخة
     * يُهمل بدل أن يُفسد المعاملة.
     */
    private fun orderFoldersForInsertion(folders: List<FolderEntity>): List<FolderEntity> {
        // Iterative parent-chain walk: recursive DFS can overflow the JVM stack on a
        // malicious/deep manifest. Duplicate IDs are normalized before ordering.
        val unique = folders.distinctBy { it.id }
        val byId = unique.associateBy { it.id }
        val ordered = ArrayList<FolderEntity>(unique.size)
        val kept = HashSet<Long>(unique.size)
        val state = HashMap<Long, Byte>(unique.size) // 0 unseen, 1 current path, 2 finished

        for (start in unique) {
            if ((state[start.id] ?: 0.toByte()) == 2.toByte()) continue
            val path = ArrayList<FolderEntity>()
            var cursor: FolderEntity? = start
            while (cursor != null && (state[cursor.id] ?: 0.toByte()) == 0.toByte()) {
                state[cursor.id] = 1
                path += cursor
                cursor = cursor.parentId?.let(byId::get)
            }

            // A node still being visited means this chain contains a parent cycle.
            // Drop the whole chain (including descendants feeding into it) safely.
            val cycleDetected = cursor != null && (state[cursor.id] ?: 0.toByte()) == 1.toByte()
            if (cycleDetected) {
                path.forEach { state[it.id] = 2 }
                continue
            }

            for (folder in path.asReversed()) {
                if (folder.parentId == null || folder.parentId in kept) {
                    ordered += folder
                    kept += folder.id
                }
                state[folder.id] = 2
            }
        }
        return ordered
    }

    // ====== التصدير: دوال مساعدة ======

    private suspend fun exportFolders(): JSONArray = JSONArray().also { array ->
        database.folderDao().getAllOnce().forEach { folder ->
            array.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("description", folder.description)
                    .put("color", folder.color)
                    .putOpt("parentId", folder.parentId)
                    .put("createdAt", folder.createdAt)
                    .put("sortOrder", folder.sortOrder)
            )
        }
    }

    private fun exportFiles(files: List<FileEntity>): JSONArray = JSONArray().also { array ->
        files.forEach { file ->
            array.put(
                JSONObject()
                    .put("id", file.id)
                    .put("name", file.name)
                    .put("extension", file.extension)
                    .put("kind", file.kind.name)
                    .put("mimeType", file.mimeType)
                    .put("size", file.size)
                    .putOpt("folderId", file.folderId)
                    .put("isFavorite", file.isFavorite)
                    .put("createdAt", file.createdAt)
            )
        }
    }

    private suspend fun exportTasks(): JSONArray = JSONArray().also { array ->
        database.taskDao().getAllOnce().forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("description", task.description)
                    .put("priority", task.priority.name)
                    .putOpt("dueDate", task.dueDate)
                    .put("isDone", task.isDone)
                    .putOpt("completedAt", task.completedAt)
                    .put("createdAt", task.createdAt)
            )
        }
    }

    private suspend fun exportNotes(): JSONArray = JSONArray().also { array ->
        database.noteDao().getAllOnce().forEach { note ->
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("content", note.content)
                    .put("isPinned", note.isPinned)
                    .put("createdAt", note.createdAt)
                    .put("updatedAt", note.updatedAt)
            )
        }
    }

    private suspend fun exportExams(): JSONArray = JSONArray().also { array ->
        database.examDao().getAllOnce().forEach { exam ->
            array.put(
                JSONObject()
                    .put("id", exam.id)
                    .put("subject", exam.subject)
                    .put("type", exam.type.name)
                    .put("date", exam.date)
                    .put("time", exam.time)
                    .put("room", exam.room)
                    .put("notes", exam.notes)
            )
        }
    }

    private suspend fun exportLectures(): JSONArray = JSONArray().also { array ->
        database.lectureDao().getAllOnce().forEach { lecture ->
            array.put(
                JSONObject()
                    .put("id", lecture.id)
                    .put("subject", lecture.subject)
                    .put("doctor", lecture.doctor)
                    .put("day", lecture.day.name)
                    .put("timeFrom", lecture.timeFrom)
                    .put("timeTo", lecture.timeTo)
                    .put("room", lecture.room)
            )
        }
    }

    private fun totalCount(root: JSONObject): Int =
        listOf("folders", "files", "tasks", "notes", "exams", "lectures")
            .sumOf { root.optJSONArray(it)?.length() ?: 0 }

    // ====== الاستيراد: دوال تحليل ======

    /** Sanitize a display name from an untrusted archive, separate from filesystem paths. */
    private fun safeImportedName(raw: String, maxLength: Int): String {
        val clean = InputValidator.sanitizeName(raw)
        return buildString(clean.length) {
            clean.forEach { char ->
                when {
                    char.code < 0x20 || char.code == 0x7f -> Unit
                    char in "/\\:*?\"<>|" -> append('_')
                    else -> append(char)
                }
            }
        }.trim().take(maxLength)
    }

    private fun parseFolders(array: JSONArray?): List<FolderEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = safeImportedName(obj.optString("name"), InputValidator.MAX_NAME_LENGTH)
            if (name.isBlank()) return@mapNotNull null
            FolderEntity(
                id = obj.optLong("id"),
                name = name,
                description = InputValidator.sanitizeText(obj.optString("description")),
                color = obj.optString("color", "#4E7D6E").take(32),
                parentId = if (obj.isNull("parentId")) null else obj.optLong("parentId"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                sortOrder = obj.optInt("sortOrder", 0)
            )
        }
    }

    private fun parseFiles(array: JSONArray?): List<FileEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = safeImportedName(obj.optString("name"), InputValidator.MAX_NAME_LENGTH)
            val path = obj.optString("filePath").trim().take(4096)
            if (name.isBlank()) return@mapNotNull null
            FileEntity(
                id = obj.optLong("id"),
                name = name,
                extension = obj.optString("extension").trim().trimStart('.')
                    .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                    .take(16)
                    .ifBlank { "bin" },
                kind = FileKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: FileKind.OTHER,
                mimeType = obj.optString("mimeType", "application/octet-stream")
                    .filterNot(Char::isISOControl).take(200).ifBlank { "application/octet-stream" },
                size = obj.optLong("size").coerceAtLeast(0L),
                folderId = if (obj.isNull("folderId")) null else obj.optLong("folderId"),
                filePath = path,
                isFavorite = obj.optBoolean("isFavorite"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseTasks(array: JSONArray?): List<TaskEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val title = InputValidator.sanitizeText(obj.optString("title")).take(InputValidator.MAX_TITLE_LENGTH)
            if (title.isBlank()) return@mapNotNull null
            val dueDateRaw = obj.optString("dueDate").takeIf { !obj.isNull("dueDate") }
            TaskEntity(
                id = obj.optLong("id"),
                title = title,
                description = InputValidator.sanitizeText(obj.optString("description")),
                priority = TaskPriority.fromStorage(obj.optString("priority")),
                dueDate = dueDateRaw?.takeIf { DateFormats.parseDateOrNull(it) != null },
                isDone = obj.optBoolean("isDone"),
                completedAt = if (obj.isNull("completedAt")) null else obj.optLong("completedAt"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseNotes(array: JSONArray?): List<NoteEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            NoteEntity(
                id = obj.optLong("id"),
                title = InputValidator.sanitizeText(obj.optString("title", "ملاحظة"))
                    .take(InputValidator.MAX_TITLE_LENGTH).ifBlank { "ملاحظة" },
                content = InputValidator.sanitizeText(obj.optString("content")),
                isPinned = obj.optBoolean("isPinned"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }

    private fun parseExams(array: JSONArray?): List<ExamEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val subject = InputValidator.sanitizeText(obj.optString("subject"))
                .take(InputValidator.MAX_TITLE_LENGTH)
            val date = obj.optString("date").trim()
            if (subject.isBlank() || DateFormats.parseDateOrNull(date) == null) return@mapNotNull null
            ExamEntity(
                id = obj.optLong("id"),
                subject = subject,
                type = ExamType.fromStorage(obj.optString("type")),
                date = date,
                time = obj.optString("time").filterNot(Char::isISOControl).take(40),
                room = InputValidator.sanitizeText(obj.optString("room")).take(200),
                notes = InputValidator.sanitizeText(obj.optString("notes"))
            )
        }
    }

    private fun parseLectures(array: JSONArray?): List<LectureEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val subject = InputValidator.sanitizeText(obj.optString("subject"))
                .take(InputValidator.MAX_TITLE_LENGTH)
            val timeFrom = obj.optString("timeFrom").filterNot(Char::isISOControl).take(40).trim()
            if (subject.isBlank() || timeFrom.isBlank()) return@mapNotNull null
            LectureEntity(
                id = obj.optLong("id"),
                subject = subject,
                doctor = InputValidator.sanitizeText(obj.optString("doctor")).take(200),
                day = Weekday.fromStorage(obj.optString("day")),
                timeFrom = timeFrom,
                timeTo = obj.optString("timeTo").filterNot(Char::isISOControl).take(40),
                room = InputValidator.sanitizeText(obj.optString("room")).take(200)
            )
        }
    }
}
