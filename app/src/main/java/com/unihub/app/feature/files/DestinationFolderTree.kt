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

    /** أبناء مستوى معيّن، مرتبون باسم غير حساس لحالة الأحرف (اتساقاً مع CloudMoveSheet). */
    fun childrenOf(folders: List<FolderEntity>, parentId: Long?): List<FolderEntity> =
        folders.filter { it.parentId == parentId }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun hasChildren(folders: List<FolderEntity>, id: Long): Boolean =
        folders.any { it.parentId == id }

    /**
     * يبسط الشجرة إلى صفوف مرئية حسب مجموعة التوسيع. حارس [seen] يوقف أي دورة في
     * parentId (بيانات فاسدة) — المجلد يظهر مرة واحدة ولا يدخل العرض في حلقة لانهائية.
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
        // بيانات فاسدة (دورات parentId أو إشارة لأب غير موجود): تظهر في المستوى
        // الأعلى بدل أن تضيع من العرض — وحارس seen يمنع أي حلقة لانهائية (اتساقاً
        // مع روح CloudFolderTree.normalise التي تعيد تأهيل الآباء المفقودين).
        folders.filter { it.id !in seen }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .forEach { add(it, 0) }
        return result
    }

}
