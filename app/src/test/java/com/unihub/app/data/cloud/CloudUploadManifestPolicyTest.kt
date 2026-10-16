package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUploadManifestPolicyTest {
    @Test fun noPendingDescriptionsDoNotCauseManifestWrites() {
        assertFalse(CloudUploadManifestPolicy.shouldPublish(0))
        assertFalse(CloudUploadManifestPolicy.shouldPublish(0, isFinal = true))
    }

    @Test fun checkpointPublishesAtFiveFilesNotBefore() {
        assertFalse(CloudUploadManifestPolicy.shouldPublish(1))
        assertFalse(CloudUploadManifestPolicy.shouldPublish(4))
        assertTrue(CloudUploadManifestPolicy.shouldPublish(5))
    }

    @Test fun finalFlushPublishesTheRemainingPartialCheckpoint() {
        assertTrue(CloudUploadManifestPolicy.shouldPublish(1, isFinal = true))
        assertTrue(CloudUploadManifestPolicy.shouldPublish(4, isFinal = true))
    }

    @Test fun twoMegabyteFolderOfTwentyFilesNeedsFourManifestCheckpoints() {
        var pending = 0
        var writes = 0
        repeat(20) {
            pending++
            if (CloudUploadManifestPolicy.shouldPublish(pending)) {
                writes++
                pending = 0
            }
        }
        if (CloudUploadManifestPolicy.shouldPublish(pending, isFinal = true)) writes++
        assertEquals(4, writes)
    }
}