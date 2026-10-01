package com.unihub.app.data.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * اختبارات قواعد الحذف السحابي — تُشغَّل على JVM بلا شبكة ولا أندرويد.
 * كل اختبار يقابل سيناريو من سيناريوهات الحلقة 4.5 (S1..S10) وفق مصفوفة الربط
 * في وثيقة الجلسة، ويثبّت ثابتاً صريحاً (Inv) لا يجوز أن ينكسر مستقبلاً.
 */
class CloudDeleteRulesTest {

    private fun remote(
        key: String = "files/a.pdf",
        name: String = "محاضرة",
        extension: String = "pdf",
        folderKey: String? = null
    ) = RemoteCloudFile(key, 0, name, extension, 50, etag = "v1", cloudFolderKey = folderKey)

    private fun link(key: String = "files/a.pdf", localId: Long = 7) =
        CloudFileLink(localId, key, "v1:50", "f".repeat(64), 50, 0)

    private fun folder(key: String = "folder:math", name: String = "رياضيات", parent: String? = null) =
        RemoteCloudFolder(key, name, parent)

    private fun fileRow(key: String, name: String = "سجل") = JSONObject().apply {
        put("remoteKey", key); put("name", name); put("extension", "pdf")
    }

    // ── S1/S3: لا يُحذف إلا ما طُلب، والباقي يبقى كما هو حرفياً ─────────────────
    @Test fun delete_files_removes_only_requested_entries() {
        val manifest = JSONArray().put(fileRow("files/a.pdf")).put(fileRow("files/b.pdf")).put(fileRow("files/c.pdf"))
        val result = CloudManifestTools.withoutFiles(manifest, setOf("files/a.pdf", "files/c.pdf"))
        assertEquals(1, result.length())
        assertEquals("files/b.pdf", result.getJSONObject(0).getString("remoteKey"))
    }

    @Test fun delete_files_keeps_other_entries_byte_identical() {
        val kept = JSONObject().apply { put("remoteKey", "files/b.pdf"); put("name", "محاضرة 2"); put("size", 4096); put("cloudFolderKey", "folder:math") }
        val manifest = JSONArray().put(fileRow("files/a.pdf")).put(kept).put(fileRow("files/c.pdf"))
        val result = CloudManifestTools.withoutFiles(manifest, setOf("files/a.pdf", "files/c.pdf"))
        assertEquals(CloudManifestTools.canonical(kept), CloudManifestTools.canonical(result.getJSONObject(0)))
    }

    // ── S4/S7/S8: إعادة التنفيذ آمنة (Idempotent) ولا تنشئ استثناءات ────────────
    @Test fun delete_files_is_idempotent_for_missing_keys() {
        val manifest = JSONArray().put(fileRow("files/a.pdf"))
        val once = CloudManifestTools.withoutFiles(manifest, setOf("files/b.pdf"))
        val twice = CloudManifestTools.withoutFiles(once, setOf("files/b.pdf"))
        assertEquals(1, once.length()); assertEquals(1, twice.length())
    }

    @Test fun delete_files_of_empty_manifest_is_safe() {
        assertEquals(0, CloudManifestTools.withoutFiles(null, setOf("files/a.pdf")).length())
        assertEquals(0, CloudManifestTools.withoutFiles(JSONArray(), setOf("files/a.pdf")).length())
    }

    // ── S10: المفاتيح غير المُكتشفة تُرفض قبل أي طلب شبكي ───────────────────────
    @Test fun delete_targets_filter_unknown_keys() {
        val known = listOf(remote("files/a.pdf"))
        assertTrue(CloudDeleteRules.targets(known, listOf("files/ghost.pdf")).isEmpty())
    }

    @Test fun delete_targets_keep_order_deduplicate_and_map_to_discovered_files() {
        val known = listOf(remote("files/a.pdf"), remote("files/b.pdf"))
        val targets = CloudDeleteRules.targets(known, listOf("files/b.pdf", "files/a.pdf", "files/b.pdf"))
        assertEquals(listOf("files/b.pdf", "files/a.pdf"), targets.map { it.remoteKey })
    }

    // ── Inv9: كائنات النظام محميّة بنيوياً ─────────────────────────────────────
    @Test fun delete_targets_reject_system_object_keys() {
        val system = listOf(
            remote(CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY),
            remote(CloudflareR2Config.REMOTE_META_OBJECT_KEY),
            remote(CloudflareR2Config.REMOTE_BACKUP_OBJECT_KEY)
        )
        assertTrue(CloudDeleteRules.targets(system, system.map { it.remoteKey }).isEmpty())
        assertFalse(CloudDeleteRules.isDeletableObjectKey(CloudflareR2Config.REMOTE_MANIFEST_OBJECT_KEY))
        assertFalse(CloudDeleteRules.isDeletableObjectKey("مجلد/"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey(""))
        assertTrue(CloudDeleteRules.isDeletableObjectKey("files/a.pdf"))
    }

    // ── S2/S9: حذف المجلد مشروط بالفراغ وبأنه من مجلدات البيان ─────────────────
    @Test fun folder_delete_requires_empty_tree() {
        val math = folder("folder:math")
        val child = folder("folder:math-sub", parent = "folder:math")
        val inside = listOf(remote("files/a.pdf", folderKey = "folder:math-sub"))
        // ملف داخل مجلد فرعي ⇒ الحذف مرفوض لأبيه (ينظر الحذف إلى الشجرة كاملة)
        assertEquals(
            "المجلد غير فارغ (1 ملف)؛ انقل ما فيه أو احذفه أولاً",
            CloudDeleteRules.folderDeletionBlockReason(math, inside, listOf(math, child))
        )
        // مجلد بلا ملفات وإن كان يحوي مجلدات فرعية فارغة ⇒ الحذف مسموح ويمتد للفرع الفارغ
        assertNull(CloudDeleteRules.folderDeletionBlockReason(math, emptyList(), listOf(math, child)))
    }

    @Test fun folder_delete_is_allowed_for_empty_app_folder_only() {
        val math = folder("folder:math")
        assertNull(CloudDeleteRules.folderDeletionBlockReason(math, emptyList(), listOf(math)))
    }

    @Test fun folder_delete_rejects_path_and_legacy_folders() {
        val path = folder("path:2026/محاضرات", name = "محاضرات")
        val legacy = folder("legacy:3", name = "مجلد محلي")
        val reasonPath = CloudDeleteRules.folderDeletionBlockReason(path, emptyList(), listOf(path))
        val reasonLegacy = CloudDeleteRules.folderDeletionBlockReason(legacy, emptyList(), listOf(legacy))
        assertNotNull(reasonPath); assertNotNull(reasonLegacy)
        assertTrue(reasonPath!!.contains("مسار التخزين"))
    }

    @Test fun folder_removal_drops_only_requested_subtree() {
        val manifest = JSONArray()
            .put(JSONObject().put("key", "folder:math").put("name", "رياضيات"))
            .put(JSONObject().put("key", "folder:math-sub").put("name", "فرعي").put("parentKey", "folder:math"))
            .put(JSONObject().put("key", "folder:phys").put("name", "فيزياء"))
        val removed = CloudManifestTools.withoutFolders(manifest, setOf("folder:math", "folder:math-sub"))
        assertEquals(1, removed.length())
        assertEquals("folder:phys", removed.getJSONObject(0).getString("key"))
    }

    // ── Inv1: الملفات المحلية لا تُمس؛ تُنقّى الروابط فقط ───────────────────────
    @Test fun links_pruned_only_for_deleted_keys() {
        val links = listOf(link("files/a.pdf", 1), link("files/b.pdf", 2), link("files/c.pdf", 3))
        val pruned = CloudDeleteRules.pruneLinks(links, setOf("files/a.pdf", "files/c.pdf"))
        assertEquals(1, pruned.size)
        assertEquals("files/b.pdf", pruned.first().remoteKey)
        assertEquals(2L, pruned.first().localId)
    }

    @Test fun folder_links_pruned_only_for_removed_folders() {
        val links = listOf(CloudFolderLink(1, 0, "folder:math"), CloudFolderLink(2, 0, "folder:phys"))
        val pruned = CloudDeleteRules.pruneFolderLinks(links, setOf("folder:math"))
        assertEquals(listOf("folder:phys"), pruned.map { it.remoteKey })
    }

    // ── H2 (محاكمة الحلقة 5): تكرار المفتاح يُدمج فلا يبقى سجل يتيم بعد الحذف ──
    @Test fun manifest_merge_collapses_duplicate_keys() {
        val duplicated = JSONArray().put(fileRow("files/a.pdf", "قديم")).put(fileRow("files/a.pdf", "جديد"))
        val merged = CloudManifestTools.mergeFiles(duplicated, emptyList())
        // التكرار يُدمج في سجل واحد (لا سجل يتيم يمكن أن يُفقد عند الحذف)،
        // والقيمة الفائزة هي الأخيرة — سلوك حتمي موثَّق هنا كي لا ينحدر مستقبلاً.
        assertEquals(1, merged.length())
        assertEquals("جديد", merged.getJSONObject(0).getString("name"))
        // المفتاح الواحد في الفهرس ⇒ حذفه يفرّغ الفهرس تماماً ولا يترك سجلاً يتيماً
        assertEquals(0, CloudManifestTools.withoutFiles(merged, setOf("files/a.pdf")).length())
    }

    // ── H4 (محاكمة الحلقة 5): سقف الدفعة ثابت معلن ─────────────────────────────
    @Test fun delete_operation_cap_is_declared_and_positive() {
        assertTrue(CloudDeleteRules.MAX_PER_OPERATION in 1..1000)
        assertEquals(200, CloudDeleteRules.MAX_PER_OPERATION)
    }
}
