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
 * الطلب فعليًا (المستخدم ألغى النقل، أو انتهت صلاحية العامل أو الاتصال) — لا عند كل طلب
 * ينتهي بنجاح كامل، وإلا تُغلق المقبس/الاتصال TCP+TLS فور كل جزء من الرفع أو التنزيل،
 * فيعاد التفاوض (handshake) من الصفر لكل قطعة (chunk) تالية بدل إعادة استخدام نفس
 * الاتصال (keep-alive) كما يفعل أي عميل HTTP سليم. هذا بالذات كان سبب رئيسيًا لبطء
 * الرفع/التنزيل الملحوظ: كل 5 ميجابايت (حجم القطعة) كانت تدفع ثمن اتصال جديد كاملاً.
 *
 * ملاحظة حرجة (إصلاح عطل حقيقي): العلم `finishedNormally` يجب أن يُرفع فقط عندما يُكمل
 * [block] عمله فعليًا دون أي استثناء — إلغاء أو خطأ. رفعه داخل `finally` بلا شرط (كما في
 * نسخة سابقة) يجعل الإلغاء نفسه يُعامَل كـ«انتهاء طبيعي» فلا يُقفَل المقبس، فيعود اتصال
 * نصف مقروء (Partial read) إلى مجمّع الاتصالات ويُعاد استخدامه لاحقًا فيُفسِد الطلب
 * التالي — وهذا تحديدًا ما يُفسِّر «يتوقف الرفع ثم يرجع من جديد» حين يُلغى نقل سابق.
 */
internal suspend fun <T> withCancellableConnection(connection: HttpURLConnection, block: suspend () -> T): T = coroutineScope {
    val finishedNormally = AtomicBoolean(false)
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { if (!finishedNormally.get()) connection.disconnect() }
    }
    val result = try {
        block()
    } catch (error: Throwable) {
        // أي خروج غير طبيعي (إلغاء أو IOException أو خطأ HTTP) يبقي finishedNormally=false
        // عمدًا؛ الحارس سيُغلق المقبس فعليًا بدل تسريبه صالحًا ظاهريًا لمجمّع الاتصالات.
        withContext(NonCancellable) { watcher.cancelAndJoin() }
        throw error
    }
    // لم يُرفع العلم إلا هنا، بعد اكتمال [block] فعليًا بلا أي استثناء من أي نوع.
    finishedNormally.set(true)
    withContext(NonCancellable) { watcher.cancelAndJoin() }
    result
}
