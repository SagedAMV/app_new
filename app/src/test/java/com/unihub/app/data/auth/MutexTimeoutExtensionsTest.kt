package com.unihub.app.data.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * يثبت إصلاح عطل تقرير المستخدم: رفع مجلد "Islamic culture" فشل برسالة
 * "جارٍ تنفيذ عملية مصادقة أخرى" بينما الحساب (مشرف) سليم ومتصل فعلاً.
 *
 * السبب الجذري: CloudAuthManager.busyOperation كان يستعمل Mutex.tryLock() — فشل فوري
 * عند أي تداخل، حتى تداخلًا طبيعيًا بين نبض التحقق الدوري (CloudSyncManager.setForeground
 * كل 20 ثانية) وإجراء يبدأه المستخدم (رفع/تنزيل). هذا الملف يختبر الدالة الصافية التي حلّت
 * محل ذلك السلوك: [tryLockWithTimeout] يجب أن تنتظر بدل الفشل الفوري.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MutexTimeoutExtensionsTest {

    @Test fun waitsForABriefHolderInsteadOfFailingImmediately() = runTest {
        // محاكاة: نبض التحقق الدوري يحجز القفل لمدة قصيرة (كاستدعاء شبكة طبيعي)
        val mutex = Mutex()
        mutex.lock()
        val second = async {
            // هذا بالضبط ما كان CloudAuthManager.requirePermission/verifyActiveSessionWithCloud
            // يفعله أثناء رفع ملف: محاولة دخول القفل بينما عملية أخرى تستعمله فعلاً.
            mutex.tryLockWithTimeout(timeoutMs = 5_000)
        }
        // العملية الأولى (النبض الدوري) تُنهي عملها القصير وتُحرر القفل
        delay(200)
        mutex.unlock()

        // الإصلاح: العملية الثانية (رفع المجلد) يجب أن تنجح بعد الانتظار، لا أن تفشل فورًا.
        assertTrue("يجب أن ينتظر القفل القصير بدل الفشل الفوري كأن المستخدم غير مسؤول", second.await())
    }

    @Test fun reportsBusyOnlyWhenLockStaysHeldForTheWholeTimeout() = runTest {
        // تداخل حقيقي (قفل عالق فعلاً): يجب أن يُعاد false بعد المهلة، لا أن ينتظر للأبد.
        val mutex = Mutex()
        mutex.lock() // لا يُحرَّر أبدًا خلال هذا الاختبار

        val acquired = mutex.tryLockWithTimeout(timeoutMs = 300)

        assertFalse("قفل عالق فعلاً طوال المهلة يجب أن يُعامل كمشغول حقًا", acquired)
    }

    @Test fun manyShortOverlappingOperationsAllEventuallySucceedSequentially() = runTest {
        // محاكاة رفع مجلد بعدة ملفات بينما نبض التحقق الدوري يحاول الدخول عدة مرات متتالية:
        // كل محاولة قصيرة ومتتابعة، ولا يجوز أن تفشل أي منها بسبب تداخل عابر.
        val mutex = Mutex()
        var successes = 0
        val jobs = (1..10).map {
            async {
                if (mutex.tryLockWithTimeout(timeoutMs = 2_000)) {
                    try {
                        successes++
                        delay(10) // عمل قصير يحاكي طلب شبكة سريعًا
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        }
        jobs.forEach { it.await() }

        assertTrue("كل العمليات المتزامنة القصيرة يجب أن تنجح بالتسلسل بلا فشل عابر واحد", successes == 10)
    }
}
