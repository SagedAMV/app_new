package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * قاعدة سقف حجم الرفع للملف الواحد (50 ميجابايت): ملف واحد يتجاوزها يُتجاهل مع رسالة
 * واضحة، وبقية الملفات المحددة في نفس الدفعة تُرفع عادةً (راجع CloudSyncManager.uploadSelected
 * و CloudUploadConfirmationSheet لمكان استخدام هذه القاعدة فعليًا).
 */
class CloudFileUploadLimitTest {

    @Test fun exactlyFiftyMegabytesIsAllowed() {
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(CloudFileRules.MAX_UPLOAD_FILE_BYTES))
    }

    @Test fun oneByteOverFiftyMegabytesIsRejected() {
        assertTrue(CloudFileRules.exceedsUploadSizeLimit(CloudFileRules.MAX_UPLOAD_FILE_BYTES + 1))
    }

    @Test fun smallAndZeroByteFilesAreAllowed() {
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(0L))
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(20L * 1024 * 1024))
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(49L * 1024 * 1024))
    }

    @Test fun wellOverTheLimitIsRejected() {
        assertTrue(CloudFileRules.exceedsUploadSizeLimit(120L * 1024 * 1024))
    }

    @Test fun limitIsExactlyFiftyMebibytes() {
        assertTrue(CloudFileRules.MAX_UPLOAD_FILE_BYTES == 50L * 1024 * 1024)
    }

    @Test fun oversizedFilesDoNotInflateUploadBatchProgressTotal() {
        val expected = 20L * 1024 * 1024 + 40L * 1024 * 1024 + CloudFileRules.MAX_UPLOAD_FILE_BYTES
        assertEquals(
            expected,
            CloudFileRules.uploadBatchBytes(
                listOf(20L * 1024 * 1024, CloudFileRules.MAX_UPLOAD_FILE_BYTES + 1, 40L * 1024 * 1024,
                    CloudFileRules.MAX_UPLOAD_FILE_BYTES, -1L)
            )
        )
    }
}
