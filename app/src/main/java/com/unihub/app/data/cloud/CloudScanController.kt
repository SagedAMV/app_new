package com.unihub.app.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** لا تُصف طلبات الفحص وراء بعضها. الطلبات المتزامنة تشترك في طلب واحد محدود المهلة. */
class CloudScanController<T>(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 30_000L,
    private val counts: (T) -> Pair<Int, Int>,
    private val fetch: suspend () -> T
) {
    private val gate = Mutex()
    private var active: Deferred<Result<T>>? = null
    private val _state = MutableStateFlow(CloudScanState())
    val state: StateFlow<CloudScanState> = _state.asStateFlow()

    suspend fun execute(showLoading: Boolean = true): Result<T> {
        val request = gate.withLock {
            active?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
                val before = _state.value
                if (showLoading || before.phase == CloudScanPhase.IDLE) {
                    _state.value = before.copy(phase = CloudScanPhase.SCANNING, message = "جارٍ فحص السحابة…")
                }
                try {
                    val result = try {
                        withTimeout(timeoutMs) { Result.success(fetch()) }
                    } catch (_: TimeoutCancellationException) {
                        Result.failure(CloudScanTimeoutException())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    result.fold(
                        onSuccess = { snapshot ->
                            val (files, folders) = counts(snapshot)
                            _state.value = CloudScanState(
                                phase = if (files == 0 && folders == 0) CloudScanPhase.EMPTY else CloudScanPhase.READY,
                                checkedAt = System.currentTimeMillis(), serverFileCount = files, serverFolderCount = folders,
                                message = if (files == 0 && folders == 0) "لا توجد ملفات أو مجلدات في السحابة"
                                    else "$folders مجلد • $files ملف على الخادم"
                            )
                        },
                        onFailure = { error ->
                            _state.value = before.copy(
                                phase = if (error is CloudBusyException) CloudScanPhase.BUSY else CloudScanPhase.ERROR,
                                message = error.message ?: "تعذّر فحص السحابة"
                            )
                        }
                    )
                    result
                } finally {
                    // الإلغاء أو أي استثناء غير متوقع لا يترك شاشة في SCANNING.
                    if (_state.value.phase == CloudScanPhase.SCANNING) {
                        _state.value = before.copy(phase = CloudScanPhase.ERROR, message = "أُوقف الفحص؛ يمكنك إعادة المحاولة")
                    }
                }
            }.also { active = it }
        }
        request.start()
        return request.await()
    }
}
