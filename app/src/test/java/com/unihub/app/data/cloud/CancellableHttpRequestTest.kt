package com.unihub.app.data.cloud

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class CancellableHttpRequestTest {
    private class FakeConnection : HttpURLConnection(URL("https://example.invalid")) {
        @Volatile var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
    }
    @Test fun scanDeadlineDisconnectsTheUnderlyingRequest() = runTest {
        val connection = FakeConnection()
        try { withTimeout(1000) { withCancellableConnection(connection) { delay(60_000) } } }
        catch (_: TimeoutCancellationException) { }
        assertTrue(connection.disconnected)
    }
    @Test fun successfulRequestAlsoClosesResources() = runTest {
        val connection = FakeConnection()
        assertEquals("ok", withCancellableConnection(connection) { "ok" })
        assertTrue(connection.disconnected)
    }
}
