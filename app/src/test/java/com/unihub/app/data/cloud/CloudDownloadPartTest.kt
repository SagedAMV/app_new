package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبار قرار «الاستكمال بدل الإعادة من الصفر» — وهو جوهر عطلين أبلغ عنهما المستخدم:
 * أزرار لا تعمل، والتنزيل يبدأ من جديد بعد كل انقطاع. الطبقة هنا نقية بلا Android وبلا شبكة.
 */
class CloudDownloadPartTest {

    // ── اسم الجزء: ثابت، آمن، بلا تهريب مسار ────────────────────────────────────────
    @Test
    fun partNameIsStableForTheSameKey() {
        assertEquals(CloudDownloadPart.fileName("unihub/2024/محاضرة.pdf"), CloudDownloadPart.fileName("unihub/2024/محاضرة.pdf"))
    }

    @Test
    fun partNameCannotEscapeTheStagingDirectory() {
        val name = CloudDownloadPart.fileName("../../../../etc/passwd")
        assertFalse("لا فاصل مسار ولا نقطة عليا في الاسم", name.contains('/') || name.contains('\\'))
        assertFalse(name.startsWith("."))
        assertTrue(name.endsWith(CloudDownloadPart.SUFFIX))
        assertEquals(File("/staging").resolve(name).parentFile, File("/staging"))
    }

    @Test
    fun keysDifferingOnlyBySeparatorsDoNotCollide() {
        // `a/b` و `a_b` يستبدلان إلى نفس الحروف الآمنة؛ البصمة القصيرة تفصل بينهما
        assertTrue(CloudDownloadPart.fileName("a/b") != CloudDownloadPart.fileName("a_b"))
    }

    @Test
    fun longKeysAreCappedSoTheFileNameStaysValid() {
        val name = CloudDownloadPart.fileName("x".repeat(400))
        assertTrue("طول الاسم ${name.length}", name.length < 120)
    }

    @Test
    fun fileForUsesTheStagingFolderOnly() {
        val staging = File("/data/user/0/com.unihub.app/files/cloud-downloads")
        val part = CloudDownloadPart.fileFor(staging, "k/e.pdf")
        assertEquals(staging, part.parentFile)
        assertTrue(part.name.endsWith(".part"))
    }

    // ── القرار: متى نكمل ومتى نستسلم ونبدأ من الصفر ────────────────────────────────
    @Test
    fun noPartMeansRestart() {
        assertTrue("بلا جزء لا شيء نُكمل منه", CloudDownloadPart.plan(0L, 1000L) is CloudDownloadPart.Resume.Restart)
        assertTrue(CloudDownloadPart.plan(-5L, 1000L) is CloudDownloadPart.Resume.Restart)
    }

    @Test
    fun partialFileResumesFromItsLength() {
        val plan = CloudDownloadPart.plan(4096L, 10_000L)
        assertTrue(plan is CloudDownloadPart.Resume.Continue)
        assertEquals(4096L, (plan as CloudDownloadPart.Resume.Continue).from)
    }

    @Test
    fun unknownExpectedSizeRefusesToResume() {
        // بلا حجم معلَن لا نعرف أين ينتهي المقطع، فاللصق قد يخلط نسختين — نعيد من الصفر
        assertTrue(CloudDownloadPart.plan(4096L, null) is CloudDownloadPart.Resume.Restart)
        assertTrue(CloudDownloadPart.plan(4096L, 0L) is CloudDownloadPart.Resume.Restart)
    }

    @Test
    fun oversizedPartIsRejectedNotTrusted() {
        // جزء أكبر من الملف المعلن = تالف أو من نسخة أخرى؛ يُعاد وإلا صار الملف النهائي خاطئًا
        assertTrue(CloudDownloadPart.plan(2000L, 1000L) is CloudDownloadPart.Resume.Restart)
        assertTrue("المساواة تعني اكتمل: لا يُستكمل فوقه", CloudDownloadPart.plan(1000L, 1000L) is CloudDownloadPart.Resume.Restart)
    }

    @Test
    fun stalePartsAreForgottenAfterTheRetentionWindow() {
        val now = System.currentTimeMillis()
        assertFalse(CloudDownloadPart.isStale(now, now - 60_000L))
        assertFalse(CloudDownloadPart.isStale(now, now - CloudDownloadPart.MAX_AGE_MS + 60_000L))
        assertTrue(CloudDownloadPart.isStale(now, now - CloudDownloadPart.MAX_AGE_MS - 60_000L))
    }
}
