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
    fun multiFileBatchIncludesCompletedFilesAndCurrentFileFraction() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(CloudTransferKind.DOWNLOAD, "notes.pdf", 3, 4, 50, 100)
        )
        assertEquals(62, progress.percentage)
        assertEquals(50, progress.currentFilePercentage)
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
        // (200 bytes from earlier files + 300 bytes of the active file) / 1000 total bytes.
        assertEquals(50, progress.percentage)
    }

    @Test
    fun fullBytesDoNotClaimBatchCompleteBeforeManagerFinishes() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(CloudTransferKind.DOWNLOAD, "last.pdf", 3, 3, 100, 100)
        )
        assertEquals(99, progress.percentage)
    }

    @Test
    fun preparationPhaseDoesNotReportHundredPercent() {
        val progress = CloudTransferNotificationProgressFactory.from(
            CloudTransferState(CloudTransferKind.UPLOAD, "report.pdf", 1, 2, 0, 2_000, "تحضير بصمة report.pdf")
        )
        assertEquals(0, progress.percentage)
        assertTrue(progress.detail.contains("تحضير بصمة"))
    }
}
