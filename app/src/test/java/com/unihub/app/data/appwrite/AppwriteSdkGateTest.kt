package com.unihub.app.data.appwrite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppwriteSdkGateTest {
    @Test fun cancelledSdkCallFinishesBeforeNextSessionCanWriteCookies() = runBlocking {
        val gate = AppwriteSdkGate()
        val started = CompletableDeferred<Unit>()
        val httpResponse = CompletableDeferred<Unit>()
        var firstPublished = false
        var secondEntered = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.await { started.complete(Unit); httpResponse.await() }
            firstPublished = true
        }
        started.await()
        first.cancel()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.await { secondEntered = true; "new-session" }
        }
        assertFalse(secondEntered)
        httpResponse.complete(Unit)
        first.join()
        assertEquals("new-session", second.await())
        assertFalse(firstPublished)
        assertTrue(first.isCancelled)
    }
    @Test fun cancelledQueuedCallNeverReachesSdk() = runBlocking {
        val gate = AppwriteSdkGate()
        val finish = CompletableDeferred<Unit>()
        val running = launch(start = CoroutineStart.UNDISPATCHED) { gate.await { finish.await() } }
        var queuedRan = false
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { gate.await { queuedRan = true } }
        queued.cancelAndJoin()
        finish.complete(Unit)
        running.join()
        assertFalse(queuedRan)
    }
    @Test fun sdkFailureReleasesGate() = runBlocking {
        val gate = AppwriteSdkGate()
        assertTrue(runCatching { gate.await { throw IllegalStateException("synthetic") } }.isFailure)
        assertEquals(7, gate.await { 7 })
    }
}
