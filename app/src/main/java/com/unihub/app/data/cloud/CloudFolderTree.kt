package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FolderEntity
import org.json.JSONArray
import org.json.JSONObject

/** شجرة نقية بمعرّفات سحابية مستقلة؛ تتحمل الآباء المفقودين والدورات. */
object CloudFolderTree {
    fun normalise(folders: List<RemoteCloudFolder>): List<RemoteCloudFolder> {
        val byKey = folders.filter { it.key.isNotBlank() && it.name.isNotBlank() }.associateBy { it.key }
        return byKey.values.map { folder ->
            var parent = folder.parentKey
            val seen = mutableSetOf(folder.key)
            var cursor = parent
            while (cursor != null) {
                if (!seen.add(cursor) || cursor !in byKey) { parent = null; break }
                cursor = byKey[cursor]?.parentKey
            }
            folder.copy(parentKey = parent)
        }.sortedBy { it.name }
    }

    fun descendants(folders: List<RemoteCloudFolder>, key: String): Set<String> {
        val children = folders.groupBy { it.parentKey }
        val result = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(key)
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (result.add(next)) children[next].orEmpty().forEach { queue.add(it.key) }
        }
        return result
    }

    fun ancestors(folders: List<RemoteCloudFolder>, key: String?): List<RemoteCloudFolder> {
        val byKey = normalise(folders).associateBy { it.key }
        val result = mutableListOf<RemoteCloudFolder>()
        val seen = mutableSetOf<String>()
        var cursor = key
        while (cursor != null && seen.add(cursor)) {
            val folder = byKey[cursor] ?: break
            result += folder
            cursor = folder.parentKey
        }
        return result.asReversed()
    }

    fun filesWithin(files: List<RemoteCloudFile>, folders: List<RemoteCloudFolder>, key: String): List<RemoteCloudFile> {
        val ids = descendants(folders, key)
        return files.filter { it.cloudFolderKey in ids }
    }

    /** رفع المحدد فقط، مع أبناء المجلد المختار وأسلافه اللازمة لحفظ المسار. */
    fun plan(title: String, allFiles: List<FileEntity>, allFolders: List<FolderEntity>, fileIds: Set<Long>, folderIds: Set<Long>): CloudUploadPlan {
        val children = allFolders.groupBy { it.parentId }
        val includedFolders = linkedSetOf<Long>()
        val queue = java.util.ArrayDeque<Long>()
        folderIds.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (includedFolders.add(id)) children[id].orEmpty().forEach { queue.add(it.id) }
        }
        val selected = allFiles.filter { it.id in fileIds || it.folderId in includedFolders }
        val byId = allFolders.associateBy { it.id }
        (includedFolders.toList() + selected.mapNotNull { it.folderId }).forEach { first ->
            var id: Long? = first
            val visited = mutableSetOf<Long>()
            while (id != null && visited.add(id)) {
                includedFolders += id
                id = byId[id]?.parentId
            }
        }
        return CloudUploadPlan(title, selected.mapTo(linkedSetOf()) { it.id }, includedFolders,
            selected.fold(0L) { sum, file -> Math.addExact(sum, file.size.coerceAtLeast(0)) })
    }

    fun foldersFromManifest(manifest: JSONObject?, files: List<RemoteCloudFile>, objects: List<R2ObjectSummary>): List<RemoteCloudFolder> {
        val result = linkedMapOf<String, RemoteCloudFolder>()
        val cloud = manifest?.optJSONArray("cloudFolders") ?: JSONArray()
        for (i in 0 until cloud.length()) cloud.optJSONObject(i)?.let(::folderFromJson)?.let { result[it.key] = it }
        val legacy = manifest?.optJSONArray("folders") ?: JSONArray()
        val byId = (0 until legacy.length()).mapNotNull { legacy.optJSONObject(it) }.associateBy { it.optLong("id") }
        val legacyNeeded = files.mapNotNull { it.cloudFolderKey?.takeIf { key -> key.startsWith("legacy:") }?.substringAfter(':')?.toLongOrNull() }
        for (first in legacyNeeded) {
            var id: Long? = first
            val seen = mutableSetOf<Long>()
            while (id != null && seen.add(id)) {
                val row = byId[id] ?: break
                val parent = if (row.isNull("parentId")) null else row.optLong("parentId")
                result["legacy:$id"] = RemoteCloudFolder("legacy:$id", row.optString("name"), parent?.let { "legacy:$it" }, row.optLong("createdAt"), id)
                id = parent
            }
        }
        fun addPath(path: String) {
            val parts = path.split('/').filter { it.isNotEmpty() }
            var parent: String? = null
            val segments = mutableListOf<String>()
            for (part in parts) {
                segments += part
                val key = "path:${segments.joinToString("/")}"
                result[key] = RemoteCloudFolder(key, part, parent)
                parent = key
            }
        }
        files.mapNotNull { it.cloudFolderKey?.takeIf { key -> key.startsWith("path:") }?.substringAfter(':') }.forEach(::addPath)
        objects.filter { it.key.endsWith('/') && !it.key.startsWith("unihub-tests/") }.forEach { addPath(it.key.trimEnd('/')) }
        // لا نخفي ملفاً لأن بياناً قديماً يشير إلى مجلد لم يعد موجوداً.
        files.forEach { file -> file.cloudFolderKey?.let { key ->
            if (key !in result) result[key] = RemoteCloudFolder(key, file.folderName ?: "مجلد سحابي")
        } }
        return normalise(result.values.toList())
    }

    fun mergeFolders(existing: JSONArray?, updates: List<RemoteCloudFolder>): JSONArray {
        val map = linkedMapOf<String, RemoteCloudFolder>()
        if (existing != null) for (i in 0 until existing.length()) existing.optJSONObject(i)?.let(::folderFromJson)?.let { map[it.key] = it }
        updates.forEach { map[it.key] = it }
        return JSONArray().also { array -> normalise(map.values.toList()).forEach { array.put(folderToJson(it)) } }
    }
}

internal fun folderToJson(folder: RemoteCloudFolder): JSONObject = JSONObject().apply {
    put("key", folder.key); put("name", folder.name); put("parentKey", folder.parentKey ?: JSONObject.NULL)
    put("createdAt", folder.createdAt); put("legacyId", folder.legacyId ?: JSONObject.NULL)
}
internal fun folderFromJson(json: JSONObject): RemoteCloudFolder? {
    val key = json.optString("key"); val name = json.optString("name")
    if (key.isBlank() || name.isBlank()) return null
    return RemoteCloudFolder(key, name, if (json.isNull("parentKey")) null else json.optString("parentKey"),
        json.optLong("createdAt"), if (json.isNull("legacyId")) null else json.optLong("legacyId"))
}
