package com.unihub.app.feature.auth

import com.unihub.app.data.auth.CloudAuthRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AuthInteractionRulesTest {
    @Test
    fun overlappingChecksAreCoalescedInsteadOfQueued() = runBlocking {
        val gate = PendingCheckGate()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runIfIdle { calls++; release.await(); true }
        }
        val second = gate.runIfIdle { calls++; false }
        assertNull(second)
        assertEquals(1, calls)
        release.complete(Unit)
        assertTrue(first.await() == true)
        assertTrue(gate.runIfIdle { calls++; true } == true)
        assertEquals(2, calls)
    }

    @Test
    fun cancellationReleasesTheGate() = runBlocking {
        val gate = PendingCheckGate()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.runIfIdle { awaitCancellation() } }
        first.cancelAndJoin()
        assertEquals(42, gate.runIfIdle { 42 })
    }

    @Test
    fun failureReleasesTheGate() = runBlocking {
        val gate = PendingCheckGate()
        try { gate.runIfIdle { throw IOException("synthetic failure") } } catch (_: IOException) { }
        assertFalse(gate.runIfIdle { false } ?: true)
    }

    @Test
    fun backoffIsBoundedAndSuccessClearsFailures() {
        val backoff = ApprovalPollingBackoff()
        assertEquals(30_000L, backoff.nextDelayMs(false))
        assertEquals(60_000L, backoff.nextDelayMs(false))
        repeat(10) { assertEquals(120_000L, backoff.nextDelayMs(false)) }
        assertEquals(15_000L, backoff.nextDelayMs(true))
        assertEquals(30_000L, backoff.nextDelayMs(true))
        assertEquals(60_000L, backoff.nextDelayMs(true))
    }

    @Test
    fun inputBoundsDoNotNormalizeThePassword() {
        assertNull(AuthInteractionRules.inputError("alice", "  exact password 2026  "))
        assertTrue(AuthInteractionRules.inputError(" ", "password") != null)
        assertTrue(AuthInteractionRules.inputError("alice", " ") != null)
        assertTrue(AuthInteractionRules.inputError("x".repeat(CloudAuthRules.MAX_USERNAME_LENGTH + 1), "password") != null)
        assertTrue(AuthInteractionRules.inputError("alice", "x".repeat(CloudAuthRules.MAX_PASSWORD_LENGTH + 1)) != null)
    }
}
