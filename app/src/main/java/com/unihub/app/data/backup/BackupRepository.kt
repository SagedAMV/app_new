package com.unihub.app.data.backup

import android.content.Context
import android.net.Uri
import com.unihub.app.core.common.DateFormats
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
import com.unihub.app.notifications.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * النسخ الاحتياطي (تصدير/استيراد JSON).
 * التحسينات عن التطبيق المرجعي:
 * 1) تنسيق مبني على دوال مساعدة بدل كتلة واحدة من 200 سطر.
 * 2) الاستيراد يعيد جدولة كل التذكيرات من الصفر (إلغاء الكل ثم جدولة القادم)
 *    حتى لا تبقى إشعارات شبحية لبيانات حُذفت.
 * 3) الأنواع المخزنة أسماء enums (قابلة للتطور دون كسر التوافق).
 * ملاحظة: ملفات المستخدمين الفيزيائية لا تُضمَّن في النسخة (بيانات وصفية فقط)،
 * وتبقى مساراتها صالحة ما دام التطبيق مثبتاً على نفس الجهاز.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: UniHubDatabase,
    private val reminderScheduler: ReminderScheduler
) {

    companion object {
        const val SCHEMA_VERSION = 3
        private const val TAG = "BackupRepository"
    }

    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject().apply {
                put("app", "unihub")
                put("schemaVersion", SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("folders", exportFolders())
                put("files", exportFiles())
                put("tasks", exportTasks())
                put("notes", exportNotes())
                put("exams", exportExams())
                put("lectures", exportLectures())
            }

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: throw IOException("تعذّر فتح ملف الوجهة للكتابة")

            totalCount(root)
        }.onFailure {
            android.util.Log.e(TAG, "فشل التصدير", it)
        }
    }

    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: throw IOException("تعذّر قراءة ملف النسخة الاحتياطية")

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

            // التحليل الكامل قبل أي حذف — لا نمس البيانات الحالية إلا بعد التأكد
            val folders = parseFolders(root.optJSONArray("folders"))
            val files = parseFiles(root.optJSONArray("files"))
            val tasks = parseTasks(root.optJSONArray("tasks"))
            val notes = parseNotes(root.optJSONArray("notes"))
            val exams = parseExams(root.optJSONArray("exams"))
            val lectures = parseLectures(root.optJSONArray("lectures"))

            database.runInTransaction {
                val folderDao = database.folderDao()
                val fileDao = database.fileDao()
                folderDao.deleteAll() // يحذف الملفات تبعاً عبر CASCADE
                database.taskDao().deleteAll()
                database.noteDao().deleteAll()
                database.examDao().deleteAll()
                database.lectureDao().deleteAll()

                folders.forEach { folderDao.insert(it) }
                files.forEach { fileDao.insert(it) }
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

            folders.size + files.size + tasks.size + notes.size + exams.size + lectures.size
        }.onFailure {
            android.util.Log.e(TAG, "فشل الاستيراد", it)
        }
    }

    // ====== التصدير ======

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

    private suspend fun exportFiles(): JSONArray = JSONArray().also { array ->
        database.fileDao().getAllOnce().forEach { file ->
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

    // ====== الاستيراد ======

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
                kind = FileKind.entries.firstOrNull { it.name == obj.optString("kind") }
                    ?: FileKind.OTHER,
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
