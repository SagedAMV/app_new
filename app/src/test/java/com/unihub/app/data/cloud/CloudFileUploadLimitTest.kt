package com.unihub.app.data.cloud

import com.unihub.app.data.appwrite.AppwriteConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * سقف الرفع يطابق حد Appwrite Cloud الفعلي (50,000,000 بايت)، حتى لا يصل ملف
 * إلى الخادم ثم يُرفض بسبب حد bucket الأقل من حد التطبيق.
 */
class CloudFileUploadLimitTest {

    @Test fun exactlyAppwriteCloudMaximumIsAllowed() {
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(CloudFileRules.MAX_UPLOAD_FILE_BYTES))
    }

    @Test fun oneByteOverAppwriteCloudMaximumIsRejected() {
        assertTrue(CloudFileRules.exceedsUploadSizeLimit(CloudFileRules.MAX_UPLOAD_FILE_BYTES + 1))
    }

    @Test fun smallAndZeroByteFilesAreAllowed() {
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(0L))
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(20L * 1024 * 1024))
        assertFalse(CloudFileRules.exceedsUploadSizeLimit(49_000_000L))
    }

    @Test fun wellOverTheLimitIsRejected() {
        assertTrue(CloudFileRules.exceedsUploadSizeLimit(120L * 1024 * 1024))
    }

    @Test fun limitMatchesTheAppwriteCloudBucketMaximum() {
        assertEquals(50_000_000L, AppwriteConfig.STORAGE_MAX_FILE_SIZE_BYTES)
        assertEquals(AppwriteConfig.STORAGE_MAX_FILE_SIZE_BYTES, CloudFileRules.MAX_UPLOAD_FILE_BYTES)
    }

    @Test fun fiftyMebibytesIsRejectedBecauseItExceedsTheCloudMaximum() {
        assertTrue(CloudFileRules.exceedsUploadSizeLimit(50L * 1024 * 1024))
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
