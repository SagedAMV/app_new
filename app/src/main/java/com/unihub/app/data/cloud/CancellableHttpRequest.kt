package com.unihub.app.data.cloud

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

/** withTimeout وحده لا يوقظ read/write المتزامنة. هذا الحارس يغلق المقبس عند إلغاء الطلب. */
internal suspend fun <T> withCancellableConnection(connection: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { connection.disconnect() }
    }
    try { block() } finally {
        withContext(NonCancellable) { watcher.cancelAndJoin(); connection.disconnect() }
    }
}
