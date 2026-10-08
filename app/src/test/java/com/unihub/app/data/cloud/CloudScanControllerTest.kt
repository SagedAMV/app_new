package com.unihub.app.data.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudScanControllerTest {
    @Test fun emptyScanStopsLoadingAndStatesEmpty() = runTest {
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value }, fetch = { 0 to 0 })
        assertTrue(controller.execute().isSuccess)
        assertEquals(CloudScanPhase.EMPTY, controller.state.value.phase)
        assertTrue(controller.state.value.message.contains("لا توجد"))
    }
    @Test fun folderWithoutFilesIsNotAnEmptyCloud() = runTest {
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value }, fetch = { 0 to 1 })
        controller.execute()
        assertEquals(CloudScanPhase.READY, controller.state.value.phase)
    }
    @Test fun timeoutCannotLeaveAnInfiniteSpinner() = runTest {
        val controller = CloudScanController(backgroundScope, timeoutMs = 1000, counts = { value: Pair<Int, Int> -> value },
            fetch = { delay(60_000); 1 to 0 })
        val result = controller.execute()
        assertTrue(result.exceptionOrNull() is CloudScanTimeoutException)
        assertEquals(CloudScanPhase.ERROR, controller.state.value.phase)
    }
    @Test fun networkErrorIsNotMisreportedAsNoData() = runTest {
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value },
            fetch = { throw IOException("HTTP 403") })
        controller.execute()
        assertEquals(CloudScanPhase.ERROR, controller.state.value.phase)
        // العقد انقلب عمدًا: كان الاختبار يطالب بـ«403» في النص، أي رمز حالة يصل المستخدم،
        // وهذا ما تمنعه طبيعة تطبيق.md §5. الرقم يبقى في السجلّ؛ الواجهة ترى سببًا إنسانيًا.
        val message = controller.state.value.message
        assertTrue(message, CloudFailureMessages.isUserFacing(message))
        assertEquals(CloudFailureMessages.SESSION, message)
        assertFalse(message, message.contains("403"))
    }
    @Test fun duplicateRequestsDoNotCreateAQueue() = runTest {
        var calls = 0
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value },
            fetch = { calls++; delay(100); 1 to 2 })
        (1..30).map { async { controller.execute() } }.awaitAll()
        assertEquals(1, calls)
        assertEquals(CloudScanPhase.READY, controller.state.value.phase)
    }
    @Test fun busyTransferStopsScanRatherThanWaitingIndefinitely() = runTest {
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value }, fetch = { throw CloudBusyException() })
        assertTrue(controller.execute().isFailure)
        assertEquals(CloudScanPhase.BUSY, controller.state.value.phase)
    }
    @Test fun silentEmptyPollingDoesNotRestartVisibleLoading() = runTest {
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value }, fetch = { delay(100); 0 to 0 })
        controller.execute()
        val next = async { controller.execute(showLoading = false) }
        runCurrent()
        assertEquals(CloudScanPhase.EMPTY, controller.state.value.phase)
        next.await()
    }
    @Test fun failedRequestCanBeRetriedSuccessfully() = runTest {
        var attempt = 0
        val controller = CloudScanController(backgroundScope, counts = { value: Pair<Int, Int> -> value }, fetch = {
            if (++attempt == 1) throw IOException("offline")
            2 to 0
        })
        assertTrue(controller.execute().isFailure)
        assertTrue(controller.execute().isSuccess)
        assertEquals(CloudScanPhase.READY, controller.state.value.phase)
    }
    @Test fun cancellingOwnerCannotLeaveScanningState() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = CloudScanController(owner, counts = { value: Pair<Int, Int> -> value }, fetch = { awaitCancellation() })
        val request = async { controller.execute() }
        runCurrent()
        assertEquals(CloudScanPhase.SCANNING, controller.state.value.phase)
        owner.cancel()
        runCurrent()
        assertEquals(CloudScanPhase.ERROR, controller.state.value.phase)
        request.cancel()
    }
}
