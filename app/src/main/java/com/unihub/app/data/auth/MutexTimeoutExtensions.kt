package com.unihub.app.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * يحاول الحصول على [this] خلال [timeoutMs] بدل الفشل الفوري الذي يفعله [Mutex.tryLock].
 *
 * لماذا هذا ضروري: تداخل قصير بين عمليتين شرعيتين — كنبض التحقق الدوري من الجلسة كل 20 ثانية
 * (CloudSyncManager.setForeground) مقابل إجراء يبدأه المستخدم فعليًا (رفع/تنزيل/فحص صلاحية) —
 * متوقَّع وطبيعي، خصوصًا على شبكة بطيئة (VPN) تُطيل كل طلب Appwrite قصير إلى ثوانٍ. استعمال
 * [Mutex.tryLock] هناك كان يرفض العملية الثانية فورًا برسالة "جارٍ تنفيذ عملية مصادقة أخرى"
 * حتى لو كانت الأولى على وشك الانتهاء خلال أجزاء من الثانية — فيفشل رفع مجلد كامل رغم أن
 * الحساب سليم ومتصل فعلاً (انظر CloudAuthManager.busyOperation و CloudAuthBusyException).
 *
 * هذه الدالة تنتظر بدل الفشل الفوري، ولا تُرجع false إلا إذا ظل القفل محجوزًا فعلاً طوال
 * [timeoutMs] — عندها فقط هذا تداخل حقيقي يستحق إبلاغ المستخدم والتعامل معه كمشغول قابل
 * لإعادة المحاولة (retryLater) لا كفشل نهائي يستهلك محاولة.
 *
 * قابلة للاختبار محليًا على JVM بلا أي اعتماد على Android (Context/Looper/الشبكة)، بعكس
 * [com.unihub.app.data.auth.CloudAuthManager] نفسه الذي يحتاج Context وAppwrite حقيقيين.
 */
suspend fun Mutex.tryLockWithTimeout(timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        lock()
        true
    } ?: false
