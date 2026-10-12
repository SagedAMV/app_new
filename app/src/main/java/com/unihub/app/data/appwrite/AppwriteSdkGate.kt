package com.unihub.app.data.appwrite

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SDK 14's cancellation does not cancel its underlying HTTP Call. Keep auth SDK requests serialized
 * until their callback (and cookie write) has actually completed. A cancelled caller then receives
 * cancellation, never an authenticated result. File transfer requests do NOT use this gate.
 */
@Singleton
class AppwriteSdkGate @Inject constructor() {
    private val mutex = Mutex()
    suspend fun <T> await(block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        return mutex.withLock {
            val result = withContext(NonCancellable) { block() }
            caller.ensureActive()
            result
        }
    }
}
