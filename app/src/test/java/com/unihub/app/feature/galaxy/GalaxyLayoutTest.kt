package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات رياضيات تخطيط شاشة المجرة (إصلاح جلسة اليوم وفق تعليمات.md:
 * «التطبيق يتعرف على حجم شاشتي ثم يبني المجلدات داخل هذه الشاشة بحيث
 * يصغر أو يكبر بما يتناسب مع عدد المجلدات»). الدالة [computeGalaxyLayout]
 * نقية بلا أي اعتماد على أندرويد — تُختبر على JVM مباشرة.
 */
class GalaxyLayoutTest {

    /** عناقيد بسيطة بلا أبناء — عنقود لكل مجلد */
    private fun clustersOf(count: Int): List<GalaxyCluster> = List(count) { index ->
        GalaxyCluster(
            parent = FolderWithFileCount(
                folderId = index.toLong(),
                name = "مجلد $index",
                color = "#4E7D6E",
                fileCount = index,
                parentId = null
            ),
            children = emptyList()
        )
    }

    /** شاشة هاتف قياسية بكثافة 2.625 — نفس مقاييس الأجهزة الحقيقية */
    private fun layoutOf(
        count: Int,
        widthPx: Float = 1080f,
        heightPx: Float = 2000f
    ): GalaxyLayout = computeGalaxyLayout(clustersOf(count), widthPx, heightPx, density = 2.625f)

    @Test
    fun `eight clusters all fit inside the screen`() {
        // جوهر المشكلة القديمة: تظهر 4 مجلدات فقط من 8 والبقية مقصوصة
        // بلا سبيل إليها — التخطيط الجديد يجب أن يحصر الثمانية كلها في الشاشة
        val layout = layoutOf(8)
        assertTrue("الشبكة يجب أن تغطي كل العناقيد", layout.cols * layout.rows >= 8)
        assertTrue(
            "المحتوى يجب أن يلائم الشاشة (الجذر القديم: قصّ الصفوف الزائدة)",
            layout.contentWidthPx <= 1080f && layout.contentHeightPx <= 2000f
        )
    }

    @Test
    fun `few clusters grow without overflowing the screen`() {
        val layout = layoutOf(4)
        assertTrue(layout.cols * layout.rows >= 4)
        assertTrue(layout.contentWidthPx <= 1080f && layout.contentHeightPx <= 2000f)
        assertTrue("خلية واسعة عندما يقل العدد", layout.cellPx > 200f)
    }

    @Test
    fun `twenty clusters keep readable cells and allow fit-all zoom`() {
        val layout = layoutOf(20)
        val minCellPx = 104f * 2.625f
        assertTrue(
            "حجم الخلية لا ينزل عن حد القراءة",
            layout.cellPx >= minCellPx
        )
        assertTrue(
            "المحتوى أكبر من الشاشة فيتوفر معامل ملاءمة أقل من 1 للقرص",
            layout.fitAllScale < 1f
        )
        assertTrue(layout.cols * layout.rows >= 20)
    }

    @Test
    fun `single cluster stays bounded and centered-ready`() {
        val layout = layoutOf(1)
        assertTrue(layout.contentWidthPx <= 1080f && layout.contentHeightPx <= 2000f)
        assertTrue("لا تضخم بلا طعم لعنقود وحيد", layout.clusterScale <= 1.9f)
        assertEquals(1, layout.cols)
        assertEquals(1, layout.rows)
    }

    @Test
    fun `small screen with many folders still produces a sane grid`() {
        val layout = layoutOf(30, widthPx = 720f, heightPx = 1280f)
        assertTrue(layout.cols >= 1 && layout.rows >= 1)
        assertTrue(layout.cols * layout.rows >= 30)
        assertTrue(layout.cellPx > 0f && layout.gapPx > 0f)
        assertTrue(layout.contentWidthPx > 0f && layout.contentHeightPx > 0f)
        assertTrue("حد أدنى مطلق للتصغير حتى لا تضيع المجرة", layout.fitAllScale >= 0.2f)
    }

    @Test
    fun `clusters with children get larger footprints than empty ones`() {
        // بنية الأبناء تكبّر بصمة العنقود — أساس التحجيم النسبي بين الجيران
        val empty = GalaxyCluster(
            parent = FolderWithFileCount(1, "فارغ", "#4E7D6E", 0, null),
            children = emptyList()
        )
        val withChildren = GalaxyCluster(
            parent = FolderWithFileCount(2, "أم", "#4E7D6E", 0, null),
            children = List(5) { i ->
                FolderWithFileCount(100L + i, "ابن $i", "#4E7D6E", i, parentId = 2)
            }
        )
        val layout = computeGalaxyLayout(
            listOf(empty, withChildren),
            viewportWidthPx = 1080f,
            viewportHeightPx = 2000f,
            density = 2.625f
        )
        assertTrue(
            "بصمة العنقود ذي الأبناء أكبر من الفارغ",
            layout.footprintsPx[1] > layout.footprintsPx[0]
        )
    }
}
