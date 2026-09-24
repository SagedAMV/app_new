package com.unihub.app.data.backup

import android.content.Context
import android.net.Uri
import com.unihub.app.core.common.DateFormats
import com.unihub.app.core.validation.InputValidator
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

        /** حد أقصى لكل عنصر داخل الأرشيف — يمنع "قنبلة ضغط" من استهلاك الذاكرة بلا حدود */
        private val MAX_ENTRY_BYTES = InputValidator.MAX_UPLOAD_SIZE_BYTES

        private fun fileEntryName(id: Long, extension: String) =
            "$FILES_PREFIX$id.${extension.ifBlank { "bin" }}"
    }

    // ====== التصدير ======

    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val fileRecords = database.fileDao().getAllOnce()
            val manifest = JSONObject().apply {
                put("app", "unihub")
                put("schemaVersion", SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("folders", exportFolders())
                put("files", exportFiles(fileRecords))
                put("tasks", exportTasks())
                put("notes", exportNotes())
                put("exams", exportExams())
                put("lectures", exportLectures())
            }

            context.contentResolver.openOutputStream(uri)?.use { rawOut ->
                ZipOutputStream(rawOut).use { zip ->
                    // 1) بيانات وصفية
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // 2) محتوى كل ملف موجود فعلياً على القرص وقت التصدير
                    fileRecords.forEach { file ->
                        val disk = File(file.filePath)
                        if (disk.isFile) {
                            runCatching {
                                zip.putNextEntry(ZipEntry(fileEntryName(file.id, file.extension)))
                                disk.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }.onFailure {
                                android.util.Log.e(TAG, "تعذّر أرشفة الملف الفعلي #${file.id}", it)
                            }
                        }
                    }
                }
            } ?: throw IOException("تعذّر فتح ملف الوجهة للكتابة")

            totalCount(manifest)
        }.onFailure { android.util.Log.e(TAG, "فشل التصدير", it) }
    }

    // ====== الاستيراد ======

    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("تعذّر قراءة ملف النسخة الاحتياطية")
            if (bytes.size.toLong() > MAX_BACKUP_BYTES) {
                throw IOException("ملف النسخة الاحتياطية أكبر من الحد المسموح")
            }
            if (isZipArchive(bytes)) importFromZip(bytes) else importFromLegacyJson(String(bytes, Charsets.UTF_8))
        }.onFailure { android.util.Log.e(TAG, "فشل الاستيراد", it) }
    }

    /** توقيع ZIP القياسي (PK\x03\x04) — يميّز الأرشيف الجديد عن نص JSON القديم */
    private fun isZipArchive(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** مسار التوافق العكسي: نسخة JSON قديمة بلا محتوى ملفات فعلي مضمّن */
    private suspend fun importFromLegacyJson(text: String): Int {
        val root = parseAndValidateManifest(text)
        val files = parseFiles(root.optJSONArray("files"))
        return applyParsedBackup(root, files)
    }

    /** مسار الأرشيف الجديد: يعيد إنشاء نسخة فعلية من كل ملف مضمّن داخل الأرشيف */
    private suspend fun importFromZip(bytes: ByteArray): Int {
        var manifestText: String? = null
        val fileBytesByEntry = HashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val data = zip.readBounded(MAX_ENTRY_BYTES)
                when {
                    entry.name == MANIFEST_ENTRY -> manifestText = String(data, Charsets.UTF_8)
                    entry.name.startsWith(FILES_PREFIX) -> fileBytesByEntry[entry.name] = data
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val text = manifestText ?: throw IOException("النسخة الاحتياطية لا تحتوي على بيانات صالحة")
        val root = parseAndValidateManifest(text)
        val declaredFiles = parseFiles(root.optJSONArray("files"))

        // نعيد كتابة محتوى كل ملف داخل تخزين هذا الجهاز — الملفات التي فُقد
        // محتواها الفعلي وقت التصدير (كانت محذوفة خارج التطبيق) تُتخطى بأمان
        // بدل إدراج سجل يتيم يشير لملف غير موجود.
        val restoredFiles = declaredFiles.mapNotNull { fe ->
            val data = fileBytesByEntry[fileEntryName(fe.id, fe.extension)]
            if (data == null) {
                android.util.Log.w(TAG, "تخطي الملف #${fe.id} — محتواه الفعلي غير موجود داخل الأرشيف")
                return@mapNotNull null
            }
            val newPath = runCatching { fileStorage.importBytes(data, fe.name, fe.extension) }
                .getOrElse {
                    android.util.Log.e(TAG, "تعذّرت استعادة الملف الفعلي #${fe.id}", it)
                    return@mapNotNull null
                }
            fe.copy(filePath = newPath)
        }

        return applyParsedBackup(root, restoredFiles)
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
        if (version > SCHEMA_VERSION) {
            throw IOException("إصدار النسخة ($version) أحدث من إصدار التطبيق ($SCHEMA_VERSION)")
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

        // إعادة جدولة التذكيرات: إلغاء الكل ثم جدولة القادم فقط
        reminderScheduler.cancelAll()
        val todayIso = DateFormats.todayIso()
        exams.filter { it.date >= todayIso }.forEach(reminderScheduler::scheduleExamReminders)
        tasks.filter { !it.isDone && !it.dueDate.isNullOrBlank() }
            .forEach(reminderScheduler::scheduleTaskReminder)

        return orderedFolders.size + validFiles.size + tasks.size + notes.size + exams.size + lectures.size
    }

    /**
     * يرتب المجلدات بحيث يُدرج كل أب قبل أبنائه (المفتاح الأجنبي الذاتي على
     * `parentId`). بدون هذا الترتيب قد يصل الابن قبل أبيه فترفض SQLite الإدراج
     * وتفشل عملية الاستيراد كلها. المجلد الذي يشير إلى أب غير موجود في النسخة
     * يُهمل بدل أن يُفسد المعاملة.
     */
    private fun orderFoldersForInsertion(folders: List<FolderEntity>): List<FolderEntity> {
        val byId = folders.associateBy { it.id }
        val ordered = ArrayList<FolderEntity>(folders.size)
        val kept = HashSet<Long>(folders.size)
        val visited = HashSet<Long>(folders.size)

        fun visit(folder: FolderEntity) {
            if (!visited.add(folder.id)) return
            val parentId = folder.parentId
            if (parentId == null) {
                ordered += folder
                kept += folder.id
                return
            }
            val parent = byId[parentId] ?: return // يتيم: يُهمل بأمان
            visit(parent)
            if (parentId in kept) {
                ordered += folder
                kept += folder.id
            }
        }
        folders.forEach(::visit)
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
                    .put("filePath", file.filePath)
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

    private fun parseFolders(array: JSONArray?): List<FolderEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            FolderEntity(
                id = obj.optLong("id"),
                name = name.take(120),
                description = obj.optString("description"),
                color = obj.optString("color", "#4E7D6E"),
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
            val name = obj.optString("name").trim()
            val path = obj.optString("filePath").trim()
            if (name.isBlank() || path.isBlank()) return@mapNotNull null
            FileEntity(
                id = obj.optLong("id"),
                name = name,
                extension = obj.optString("extension"),
                kind = FileKind.entries.firstOrNull { it.name == obj.optString("kind") } ?: FileKind.OTHER,
                mimeType = obj.optString("mimeType", "application/octet-stream"),
                size = obj.optLong("size"),
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
            val title = obj.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            TaskEntity(
                id = obj.optLong("id"),
                title = title,
                description = obj.optString("description"),
                priority = TaskPriority.fromStorage(obj.optString("priority")),
                dueDate = if (obj.isNull("dueDate")) null else obj.optString("dueDate"),
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
                title = obj.optString("title", "ملاحظة"),
                content = obj.optString("content"),
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
            val subject = obj.optString("subject").trim()
            val date = obj.optString("date").trim()
            if (subject.isBlank() || DateFormats.parseDateOrNull(date) == null) return@mapNotNull null
            ExamEntity(
                id = obj.optLong("id"),
                subject = subject,
                type = ExamType.fromStorage(obj.optString("type")),
                date = date,
                time = obj.optString("time"),
                room = obj.optString("room"),
                notes = obj.optString("notes")
            )
        }
    }

    private fun parseLectures(array: JSONArray?): List<LectureEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val subject = obj.optString("subject").trim()
            val timeFrom = obj.optString("timeFrom").trim()
            if (subject.isBlank() || timeFrom.isBlank()) return@mapNotNull null
            LectureEntity(
                id = obj.optLong("id"),
                subject = subject,
                doctor = obj.optString("doctor"),
                day = Weekday.fromStorage(obj.optString("day")),
                timeFrom = timeFrom,
                timeTo = obj.optString("timeTo"),
                room = obj.optString("room")
            )
        }
    }
}

/** قراءة عنصر ZIP الحالي بحد أقصى للبايتات — يمنع "قنبلة ضغط" من استهلاك الذاكرة بلا حدود */
private fun ZipInputStream.readBounded(maxBytes: Long): ByteArray {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw IOException("عنصر داخل النسخة الاحتياطية أكبر من الحد المسموح")
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}
