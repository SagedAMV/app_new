import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

sealed class Outcome<T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Fail<T>(val error: Throwable) : Outcome<T>()
    val isFailure get() = this is Fail
    override fun toString(): String = when (this) {
        is Ok -> "Ok($value)"
        is Fail -> "Fail(${error.message})"
    }
}

/** Faithful copy of the ORIGINAL CloudAuthManager.busyOperation pattern (tryLock => fail fast). */
object OldManager {
    private val mutex = Mutex()
    suspend fun <T> busyOperation(block: suspend () -> T): Outcome<T> {
        if (!mutex.tryLock()) return Outcome.Fail(IOException("جارٍ تنفيذ عملية مصادقة أخرى"))
        try { return Outcome.Ok(block()) }
        finally { mutex.unlock() }
    }
}

/** FIXED pattern: queue with withLock instead of failing fast. */
object NewManager {
    private val mutex = Mutex()
    suspend fun <T> busyOperation(block: suspend () -> T): Outcome<T> = mutex.withLock {
        Outcome.Ok(block())
    }
}

fun main() = runBlocking {
    // Simulate the real scenario: a background "pulse" coroutine calls verifyActiveSessionWithCloud()
    // (a network round trip, modeled as a delay) at the same time the user taps "upload folder",
    // which internally calls verifyActiveSessionWithCloud() + requirePermission(UPLOAD) + requirePermission(MODIFY).

    println("== OLD behaviour (tryLock fail-fast) ==")
    var oldFailures = 0
    var oldSuccesses = 0
    coroutineScope {
        val backgroundPulse = launch {
            val r = OldManager.busyOperation { delay(200); "session-verified" }
            println("  background pulse -> $r")
        }
        delay(20) // user taps upload shortly after the pulse started its network call
        val userUpload = launch {
            val r = OldManager.busyOperation { delay(50); "upload-authorized" }
            println("  user upload       -> $r")
            if (r.isFailure) oldFailures++ else oldSuccesses++
        }
        backgroundPulse.join(); userUpload.join()
    }
    println("OLD: successes=$oldSuccesses failures=$oldFailures (upload wrongly rejected while unrelated background check was running)")

    println()
    println("== NEW behaviour (withLock queueing) ==")
    var newFailures = 0
    var newSuccesses = 0
    coroutineScope {
        val backgroundPulse = launch {
            val r = NewManager.busyOperation { delay(200); "session-verified" }
            println("  background pulse -> $r")
        }
        delay(20)
        val userUpload = launch {
            val r = NewManager.busyOperation { delay(50); "upload-authorized" }
            println("  user upload       -> $r")
            if (r.isFailure) newFailures++ else newSuccesses++
        }
        backgroundPulse.join(); userUpload.join()
    }
    println("NEW: successes=$newSuccesses failures=$newFailures (upload now queues behind the background check and still succeeds)")

    check(oldFailures == 1) { "expected the old buggy code to reproduce the user's failure" }
    check(newFailures == 0) { "expected the fix to remove the spurious failure" }
    println()
    println("VERIFIED: fix removes the 'جارٍ تنفيذ عملية مصادقة أخرى' spurious failure caused by lock contention.")
}
