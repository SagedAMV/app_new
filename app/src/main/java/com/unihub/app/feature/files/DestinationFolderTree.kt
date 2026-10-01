package com.unihub.app.feature.files

import com.unihub.app.data.local.entity.FolderEntity

/**
 * صف واحد في منتقي وجهة التنزيل القابل للتوسيع (جولة تعليمات.md):
 * بدل قائمة مسطحة بمسارات «فيزياء / ملخصات»، تُعرض المجلدات كشجرة — النقر على
 * مجلد يوسّعه ويظهر أبناؤه تحته بإزاحة، وهو السلوك المألوف في تطبيقات الملفات.
 *
 * دوال نقية بلا أي اعتماد على Compose حتى تُختبر محلياً (JVM) — طريقة التفحص
 * المطلوبة في تعليمات.md: التعديل يُثبت باختبارات قابلة لإعادة التشغيل.
 */
data class DestinationFolderRow(
    val folder: FolderEntity,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    val isSelected: Boolean
)

object DestinationFolderTree {

    /**
     * أبناء مستوى معيّن بترتيب المصدر كما هو (ترشيح مستقر بلا إعادة ترتيب) —
     * الترتيب يأتي من استعلامات Room (الترتيب اليدوي sortOrder ثم الأحدث)،
     * وإعادة الفرز هنا كانت تمسح ترتيب المستخدم اليدوي وتكسر عقد الاختبارات.
     */
    fun childrenOf(folders: List<FolderEntity>, parentId: Long?): List<FolderEntity> =
        folders.filter { it.parentId == parentId }

    fun hasChildren(folders: List<FolderEntity>, id: Long): Boolean =
        folders.any { it.parentId == id }

    /**
     * يبسط الشجرة إلى صفوف مرئية حسب مجموعة التوسيع.
     *
     * أبناء المجلدات المطوية لا يظهرون في الصفوف لكنهم ليسوا ضائعين: يكشفهم
     * توسيع آبائهم. وحدها المجلدات غير القابلة للبلوغ إطلاقاً (بيانات فاسدة:
     * أب مفقود أو دورة parentId) تُرقّى إلى المستوى الأعلى مرة واحدة لكل منها،
     * وحارس [seen] يمنع أي حلقة لانهائية أو تكرار في العرض.
     */
    fun rows(folders: List<FolderEntity>, expandedIds: Set<Long>, selectedId: Long?): List<DestinationFolderRow> {
        val result = mutableListOf<DestinationFolderRow>()
        val seen = mutableSetOf<Long>()
        fun add(folder: FolderEntity, depth: Int) {
            if (!seen.add(folder.id)) return
            val hasKids = hasChildren(folders, folder.id)
            val expanded = folder.id in expandedIds
            result += DestinationFolderRow(folder, depth, hasKids, expanded, folder.id == selectedId)
            if (expanded && hasKids) childrenOf(folders, folder.id).forEach { add(it, depth + 1) }
        }
        childrenOf(folders, null).forEach { add(it, 0) }

        // المسح التكميلي: قبل الإصلاح كان يُرقّي كل مجلد غير معروض إلى الأعلى —
        // فيسرب أبناء المجلدات المطوية (الخلل الذي كسر اختبارات الشجرة). الآن
        // يُرقّى فقط من يستحيل ظهوره بالتوسيع: سلسلته الأبوية لا تبلغ مجلداً
        // معروضاً ولا جذراً حقيقياً (أب مفقود أو دورة). الممرات المتكررة تلزم
        // لأن ترقية مجلد تجعل أحفاده قابلة للبلوغ فتمتنع ترقيتهم.
        val byId = folders.associateBy { it.id }
        fun attachable(start: FolderEntity): Boolean {
            val chain = mutableSetOf<Long>()
            var current: FolderEntity? = start
            while (true) {
                val node = current ?: return true         // جذر حقيقي — معروض منذ البداية
                if (!chain.add(node.id)) return false     // دورة parentId — غير قابل للبلوغ
                if (node.id in seen) return true          // سلف معروض — يظهر بالتوسيع
                val parentId = node.parentId ?: return true
                if (parentId !in byId) return false       // أب مفقود — يتيم
                current = byId[parentId]
            }
        }
        var progressed = true
        while (progressed) {
            progressed = false
            for (folder in folders) {
                if (folder.id in seen || attachable(folder)) continue
                add(folder, 0)
                progressed = true
            }
        }
        return result
    }

}
