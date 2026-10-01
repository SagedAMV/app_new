package com.unihub.app.data.cloud

import org.json.JSONArray
import org.json.JSONObject

/** قواعد نقية يمكن اختبارها خارج أندرويد. */
object CloudFileRules {
    fun isDownloaded(
        remote: RemoteCloudFile,
        link: CloudFileLink?,
        localId: Long?,
        actualLocalLength: Long?
    ): Boolean = link != null && localId == link.localId &&
        link.remoteKey == remote.remoteKey && link.remoteVersion == remote.versionToken &&
        actualLocalLength != null && actualLocalLength == link.localSize

    fun newNotifications(files: List<RemoteCloudFile>, notified: Set<String>): List<RemoteCloudFile> =
        files.filter { it.notificationToken !in notified }

    /** تسمية آمنة لا تتصادم بين هواتف أو سجلات Room مستقلة؛ المحتوى ثابت لهذه الهوية. */
    fun contentObjectKey(sha256: String, extension: String, identity: String = ""): String {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val safeExt = extension.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            .lowercase(java.util.Locale.US).ifBlank { "bin" }
        require(identity.isEmpty() || identity.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val suffix = if (identity.isEmpty()) "" else "_$identity"
        return "${CloudflareR2Config.REMOTE_FILES_PREFIX}$sha256$suffix.$safeExt"
    }
}

/**
 * قواعد الحذف السحابي — نقية وقابلة للاختبار على JVM بلا شبكة.
 * الحذف إجراء تدميري لا رجعة فيه، فكل دالة هنا تحرس ثابتاً صريحاً **قبل** أي طلب شبكي:
 *  - كائنات النظام (الفهرس/الوصف/النسخة الاحتياطية) لا تُحذف من واجهة الملفات أبداً.
 *  - لا يُحذف إلا مفتاح مُكتشف فعلاً في آخر فحص (لا مفاتيح ملفقة/قديمة).
 *  - النسخ المحلية لا تُمس؛ تُنقّى روابط الفهرس فقط.
 *  - لا يُحذف مجلد إلا إن كان من مجلدات البيان (folder:) وخالياً من الملفات.
 */
object CloudDeleteRules {
    /** سقف العملية الواحدة: يمنع تجاوز مهلة تحرير البيان فتنتج حالة نصفية غامضة. */
    const val MAX_PER_OPERATION: Int = 200

    fun isDeletableObjectKey(objectKey: String): Boolean =
        objectKey.isNotBlank() && !objectKey.endsWith("/") &&
            objectKey != CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY &&
            objectKey != CloudflareR2Config.REMOTE_META_OBJECT_KEY &&
            objectKey != CloudflareR2Config.REMOTE_BACKUP_OBJECT_KEY

    /** الملفات المكتشفة فعلاً والمطابقة للمفاتيح المطلوبة — وبدون تكرار، وبترتيب الطلب. */
    fun targets(files: List<RemoteCloudFile>, requestedKeys: Collection<String>): List<RemoteCloudFile> =
        requestedKeys.asSequence().distinct()
            .mapNotNull { key -> files.firstOrNull { it.remoteKey == key } }
            .filter { isDeletableObjectKey(it.remoteKey) }
            .toList()

    fun pruneLinks(links: List<CloudFileLink>, removedKeys: Set<String>): List<CloudFileLink> =
        links.filterNot { it.remoteKey in removedKeys }

    fun pruneFolderLinks(links: List<CloudFolderLink>, removedFolderKeys: Set<String>): List<CloudFolderLink> =
        links.filterNot { it.remoteKey in removedFolderKeys }

    /** سبب رفض حذف المجلد، أو null إن كان الحذف مسموحاً (مجلد بيان فارغ من الملفات). */
    fun folderDeletionBlockReason(
        folder: RemoteCloudFolder,
        files: List<RemoteCloudFile>,
        folders: List<RemoteCloudFolder>
    ): String? {
        if (!folder.key.startsWith("folder:")) {
            return "هذا المجلد مرتبط بمسار التخزين أو بمكتبة جهازك؛ لا يُحذف من السحابة"
        }
        val within = CloudFolderTree.filesWithin(files, folders, folder.key)
        if (within.isNotEmpty()) {
            return "المجلد غير فارغ (${within.size} ملف)؛ انقل ما فيه أو احذفه أولاً"
        }
        return null
    }
}

/** دمج ثلاثي للجداول الخفيفة: لا نستبدل تعديلاً محلياً بتعديل بعيد غير مشروط. */
object CloudManifestTools {
    val metadataTables = listOf("folders", "tasks", "notes", "exams", "lectures")

    fun mergeMetadata(local: JSONObject, remote: JSONObject?, baseline: JSONObject?): JSONObject {
        val result = JSONObject(local.toString())
        for (table in metadataTables) {
            val l = records(local.optJSONArray(table))
            val r = records(remote?.optJSONArray(table))
            val b = records(baseline?.optJSONArray(table))
            val merged = JSONArray()
            for (id in (l.keys + r.keys + b.keys).sorted()) {
                val localRow = l[id]
                val chosen = if (baseline == null || canonical(localRow) != canonical(b[id])) {
                    localRow ?: if (b[id] == null) r[id] else null
                } else {
                    r[id]
                }
                // حذف مجلد بعيد لا يحذف ملفات محلية عبر CASCADE. الحذف السحابي إجراء منفصل.
                val row = if (table == "folders" && chosen == null) localRow else chosen
                if (row != null) merged.put(JSONObject(row.toString()))
            }
            result.put(table, merged)
        }
        return result
    }

    /** لا نسوّي تعديلين متعارضين بصمت أو نصطدم بمعرّفات هاتف مستقل. */
    fun conflicts(local: JSONObject, remote: JSONObject?, baseline: JSONObject?): List<String> {
        if (remote == null) return emptyList()
        val conflicts = mutableListOf<String>()
        for (table in metadataTables) {
            val l = records(local.optJSONArray(table))
            val r = records(remote.optJSONArray(table))
            val b = records(baseline?.optJSONArray(table))
            for (id in l.keys + r.keys) {
                if (canonical(l[id]) == canonical(r[id])) continue
                val base = b[id]
                if ((base != null && canonical(l[id]) != canonical(base) && canonical(r[id]) != canonical(base)) ||
                    (base == null && l[id] != null && r[id] != null)) conflicts += "$table:$id"
            }
        }
        return conflicts
    }

    fun requireUnchangedLocalMetadata(expected: JSONObject, current: JSONObject) {
        if (!metadataEqual(expected, current)) throw java.io.IOException("تغيرت البيانات المحلية أثناء الفحص؛ لم نستبدلها. أعد الفحص والمحاولة")
    }

    /** الحفاظ على كل الملفات غير المختارة عند رفع تحديث من جهاز يحوي جزءاً من المكتبة. */
    fun mergeFiles(remote: JSONArray?, local: List<RemoteCloudFile>): JSONArray {
        val result = linkedMapOf<String, JSONObject>()
        if (remote != null) for (i in 0 until remote.length()) {
            val row = remote.optJSONObject(i) ?: continue
            val key = row.optString("remoteKey")
            if (key.isNotBlank()) result[key] = JSONObject(row.toString()).apply { remove("filePath") }
        }
        local.forEach { result[it.remoteKey] = remoteFileToJson(it) }
        return JSONArray().also { array -> result.values.forEach { array.put(it) } }
    }

    fun metadataEqual(a: JSONObject, b: JSONObject?): Boolean = b != null &&
        metadataTables.all { table ->
            val l = records(a.optJSONArray(table))
            val r = records(b.optJSONArray(table))
            l.keys == r.keys && l.all { (id, row) -> canonical(row) == canonical(r[id]) }
        }

    private fun records(array: JSONArray?): Map<Long, JSONObject> = linkedMapOf<Long, JSONObject>().apply {
        if (array != null) for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { put(it.optLong("id"), it) }
        }
    }

    /**
     * إزالة سجلات ملفات محددة من بيان الخادم مع الحفاظ على ترتيب الباقي ونصوصه كما هي.
     * تُستخدم في الحذف السحابي: لا تُلمس أي سجل غير مطلوب.
     */
    fun withoutFiles(remote: JSONArray?, keys: Set<String>): JSONArray {
        val result = JSONArray()
        if (remote != null) for (i in 0 until remote.length()) {
            val row = remote.optJSONObject(i) ?: continue
            if (row.optString("remoteKey") in keys) continue
            result.put(JSONObject(row.toString()))
        }
        return result
    }

    /** إزالة مجلدات بيان محددة (بمفاتيحها) من بيان الخادم — تُستخدم لحذف المجلدات الفارغة. */
    fun withoutFolders(existing: JSONArray?, keys: Set<String>): JSONArray {
        val result = JSONArray()
        if (existing != null) for (i in 0 until existing.length()) {
            val row = existing.optJSONObject(i) ?: continue
            if (row.optString("key") in keys) continue
            result.put(JSONObject(row.toString()))
        }
        return result
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
            canonical(value.opt(it))
        }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
