package com.unihub.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * فحص إقلاع «النسخة المثبَّتة» نفسها — لا واجهة مصطنعة.
 *
 * ثقب حقيقي في التحقق السابق: كل اختبارات الجهاز الأخرى تبني محتواها بـ
 * `createComposeRule()`، فلا تمرّ على مسار الإقلاع إطلاقاً — التطبيق
 * (`@HiltAndroidApp`)، ورسمة الحقن كاملة، وقاعدة Room، والتفضيلات، وWorkManager،
 * ونداءات `onStart/onStop` الحقيقية. ولهذا كانت النسخة المنشورة قد تُثبَّت ثم
 * تنهار (أو تبقى على شاشة فارغة) وتبقى المنصة خضراء لأن أحداً لم يفتح التطبيق.
 *
 * هذا الاختبار يشغّل [MainActivity] الحقيقي داخل عملية التطبيق الحقيقية ويثبت:
 * 1) الإقلاع البارد يصل إلى حالة الاستئناف بلا انهيار.
 * 2) دورة الحياة الكاملة (خلفية ثم عودة) لا تُسقط العملية — وهي نفس المسار الذي
 *    يشغّل/يوقف نبض المزامنة السحابية في [com.unihub.app.data.cloud.CloudSyncManager].
 *
 * ملاحظة مقصودة: لا تُستعمل ساعة Compose هنا (لا `waitUntil` ولا `waitForIdle`)،
 * لأن شاشة الدخول تحمل حركة لا نهائية (تنفّس الشعار) وانتظار «الهدوء» مع حركة
 * لا نهائية قد يعلّق الاختبار. الانتظار زمني ومحدود، والانهيار الفعلي يقتل العملية
 * فيظهر فوراً كفشل.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    @Test
    fun installedBuildSurvivesColdStartBackgroundAndResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            assertEquals(
                "النشاط الحقيقي لم يصل إلى حالة الاستئناف بعد الإقلاع البارد",
                Lifecycle.State.RESUMED,
                scenario.state
            )

            // خلفية ثم عودة: مسار لم يغطّه أي اختبار آخر (setForeground/onStop/onStart)
            scenario.moveToState(Lifecycle.State.CREATED)
            settle()
            scenario.moveToState(Lifecycle.State.RESUMED)
            settle()
            assertEquals(
                "النشاط الحقيقي لم يعد إلى حالة الاستئناف بعد العودة من الخلفية",
                Lifecycle.State.RESUMED,
                scenario.state
            )

            scenario.onActivity { activity ->
                assertFalse("النشاط بدأ بالإنهاء بعد الإقلاع", activity.isFinishing)
                assertTrue(
                    "نافذة النشاط بلا أبعاد مرسومة — شاشة فارغة لا واجهة",
                    activity.window.decorView.width > 0 && activity.window.decorView.height > 0
                )
            }
        }
    }

    /** انتظار على خيط الاختبار (لا خيط الواجهة) — يكشف الانهيار الفوري بلا هشاشة توقيت. */
    private fun settle() = Thread.sleep(SETTLE_MILLIS)

    private companion object {
        const val SETTLE_MILLIS = 2_500L
    }
}
