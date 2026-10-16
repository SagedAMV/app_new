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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * withTimeout وحده لا يوقظ read/write المتزامنة. هذا الحارس يغلق المقبس فقط عند إلغاء
 * الطلب فعليًا (المستخدم ألغى النقل، أو انتهت صلاحية العامل) — لا عند كل طلب ينتهي بنجاح
 * أو بخطأ عادي (404/409...)، وإلا تُغلق المقبس/الاتصال TCP+TLS فور كل جزء من الرفع أو
 * التنزيل، فيعاد التفاوض (handshake) من الصفر لكل قطعة (chunk) تالية بدل إعادة استخدام
 * نفس الاتصال (keep-alive) كما يفعل أي عميل HTTP سليم. هذا بالذات كان سبب رئيسيًا لبطء
 * الرفع/التنزيل الملحوظ: كل 5 ميجابايت (حجم القطعة) كانت تدفع ثمن اتصال جديد كاملاً.
 */
internal suspend fun <T> withCancellableConnection(connection: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
    val finishedNormally = AtomicBoolean(false)
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!finishedNormally.get()) connection.disconnect() }
    }
    try {
        block()
    } finally {
        // يُسجَّل الانتهاء الطبيعي قبل إلغاء الحارس حتى لا يغلق مقبسًا انتهت قراءته/كتابته
        // فعلاً وأصبح صالحًا لإعادة الاستخدام من مجمّع اتصالات النظام (HTTP keep-alive).
        finishedNormally.set(true)
        withContext(NonCancellable) { watcher.cancelAndJoin() }
    }
}
