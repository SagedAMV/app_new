package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTransferNotificationProgressTest {
    @Test
    fun noTransferStartsAtZeroAndNeverLooksComplete() {
        assertEquals(0, CloudTransferNotificationProgressFactory.from(null).percentage)
        assertEquals(0, CloudTransferNotificationProgressFactory.from(CloudTransferState()).percentage)
    }

    @Test
    fun singleLargeFileTracksRealByteProgress() {
        val quarter = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(
                kind = CloudTransferKind.UPLOAD, fileName = "video.mp4", index = 1, totalFiles = 1,
                bytesDone = 25, bytesTotal = 100, batchBytesTotal = 100
            )
        )
        val threeQuarters = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(
                kind = CloudTransferKind.UPLOAD, fileName = "video.mp4", index = 1, totalFiles = 1,
                bytesDone = 75, bytesTotal = 100, batchBytesTotal = 100
            )
        )
        assertEquals(25, quarter.percentage)
        assertEquals(75, threeQuarters.percentage)
        assertTrue(threeQuarters.percentage > quarter.percentage)
    }

    @Test
    fun multiFileBatchIncludesCompletedFilesWhenLegacyStateHasNoBatchTotal() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(CloudTransferKind.DOWNLOAD, "notes.pdf", 3, 4, 50, 100)
        )
        assertEquals(62, progress.percentage)
    }

    @Test
    fun batchProgressIsWeightedByBytesRatherThanFileCount() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(
                kind = CloudTransferKind.DOWNLOAD, fileName = "large-file.bin", index = 3, totalFiles = 4,
                bytesDone = 300, bytesTotal = 400,
                bytesCompletedBeforeCurrentFile = 200, batchBytesTotal = 1_000
            )
        )
        assertEquals(50, progress.percentage)
    }

    @Test
    fun progressClampsToNinetyNineUntilTheManagerFinishes() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(
                CloudTransferKind.DOWNLOAD, "last.pdf", 3, 3, 100, 100,
                bytesCompletedBeforeCurrentFile = Long.MAX_VALUE,
                batchBytesTotal = 100
            )
        )
        assertEquals(99, progress.percentage)
    }

    @Test
    fun preparationPhaseShowsOnlyZeroPercentInNotificationModel() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(CloudTransferKind.UPLOAD, "report.pdf", 1, 2, 0, 2_000, "Preparing")
        )
        assertEquals(0, progress.percentage)
    }
}
