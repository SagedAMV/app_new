package com.unihub.app.data.cloud

/**
 * السبب الجذري للمشكلة المُصلَحة هنا (موثّق رقمياً في CloudPulseIntervalPolicyTest):
 * كان [CloudSyncManager] يفحص خادم Supabase بفاصل **ثابت** دائماً — 20 ثانية طوال ظهور
 * الواجهة (LIVE)، أو 120 ثانية إن كانت السحابة فارغة — بصرف النظر عن وجود أي نشاط فعلي.
 * نتيجة ذلك قياسياً: أي تغيير يحدث على السحابة من جهاز آخر يصل للمستخدم بعد ما معدّله
 * 10 ثوانٍ انتظار (نصف الفاصل الثابت 20 ثانية في أسوأ/أفضل الأحوال)، بل حتى 60 ثانية
 * انتظار وسطي عند حالة "فارغة" (نصف 120 ثانية) — مع استهلاك شبكة/بطارية ثابت طوال الوقت
 * حتى لو لم يتغيّر شيء لساعات.
 *
 * الحل الذكي بدل "فحص كل كذا ثانية": فاصل **متكيّف** (Adaptive polling) مبني على حالة
 * النشاط الفعلية بدل رقم ثابت أعمى:
 *  1) أول فحص عند فتح التطبيق/عودته للواجهة: **فوري** دائماً (راجع [resetToActive] +
 *     آلية الإيقاظ الفوري [CloudSyncManager.requestImmediateSync]).
 *  2) أثناء "نافذة نشاط" (بعد أي تغيير، أو عودة ظهور، أو عودة شبكة): فحص سريع متتالٍ
 *     ([fastIntervalMs]) — لأن التغييرات غالباً تصل في دفعات (رفع عدة ملفات متتالية من
 *     جهاز آخر، تعديلات متتالية من مستخدم آخر...)، فيشعر المستخدم بأنه "لحظي".
 *  3) إن لم يتغيّر شيء لعدد جولات متتالية ([activeWindowRounds])، يتباعد الفاصل تدريجياً
 *     (نمو مضاعف محدود بسقف أقصى [idleCeilingMs]/[emptyIdleCeilingMs]) — فيوفّر شبكة
 *     وبطارية في وقت الخمول الفعلي بدل الفحص الثابت العشوائي.
 *  4) أي تغيير مكتشف يعيد الفاصل فوراً إلى [fastIntervalMs] (نافذة نشاط جديدة).
 *
 * الصنف هنا خالٍ تماماً من أي اعتماد على Android/الشبكة/الزمن الحقيقي (لا [System]،
 * لا Coroutines) — قرار تصميمي متعمّد ليكون قابلاً لاختبار وحدة حتمي 100% بدون محاكاة.
 */
class CloudPulseIntervalPolicy(
    private val fastIntervalMs: Long = DEFAULT_FAST_INTERVAL_MS,
    private val idleCeilingMs: Long = DEFAULT_IDLE_CEILING_MS,
    private val emptyIdleCeilingMs: Long = DEFAULT_EMPTY_IDLE_CEILING_MS,
    private val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    private val activeWindowRounds: Int = DEFAULT_ACTIVE_WINDOW_ROUNDS
) {
    init {
        require(fastIntervalMs > 0) { "fastIntervalMs يجب أن يكون موجباً" }
        require(idleCeilingMs >= fastIntervalMs) { "سقف الخمول يجب ألا يقل عن الفاصل السريع" }
        require(emptyIdleCeilingMs >= idleCeilingMs) { "سقف الخمول الفارغ يجب ألا يقل عن سقف الخمول العادي" }
        require(backoffMultiplier > 1.0) { "معامل التباعد يجب أن يكون أكبر من 1 ليحدث تباعد فعلي" }
        require(activeWindowRounds >= 0) { "عدد جولات النشاط يجب ألا يكون سالباً" }
    }

    @Volatile private var consecutiveNoChangeRounds = 0
    @Volatile private var currentIntervalMs = fastIntervalMs

    /** الفاصل الذي سيُستخدم لو طُلب الآن دون فحص إضافي — مفيد للعرض/الاختبار فقط. */
    val currentIntervalMsSnapshot: Long get() = currentIntervalMs

    /**
     * استدعِها فور: عودة ظهور الواجهة (onStart)، عودة الشبكة بعد انقطاع، أو طلب مزامنة
     * فوري صريح. تُجبر الجولة القادمة على أن تكون سريعة (ليست بالضرورة فورية بذاتها —
     * الفورية الحقيقية تُحقَّق عبر إيقاظ حلقة الانتظار في [CloudSyncManager]، لا هنا).
     */
    fun resetToActive() {
        consecutiveNoChangeRounds = 0
        currentIntervalMs = fastIntervalMs
    }

    /**
     * تُستدعى بعد كل محاولة فحص فعلية لحساب مدة الانتظار قبل المحاولة التالية.
     *
     * @param changed هل تغيّرت بيانات السحابة فعلياً في هذا الفحص (عدد ملفات/مجلدات/حالة)؟
     * @param isEmptyPhase هل السحابة في حالة "فارغة" حالياً؟ (سقف خمول أعلى لأنها أقل إفادة)
     * @return مدة الانتظار بالمللي ثانية قبل الفحص التالي.
     */
    fun nextDelayMs(changed: Boolean, isEmptyPhase: Boolean): Long {
        val ceiling = if (isEmptyPhase) emptyIdleCeilingMs else idleCeilingMs
        if (changed) {
            resetToActive()
            return currentIntervalMs
        }
        consecutiveNoChangeRounds += 1
        currentIntervalMs = if (consecutiveNoChangeRounds <= activeWindowRounds) {
            fastIntervalMs
        } else {
            val grown = (currentIntervalMs.toDouble() * backoffMultiplier).toLong()
            grown.coerceAtLeast(fastIntervalMs).coerceAtMost(ceiling)
        }
        return currentIntervalMs
    }

    companion object {
        /** فحص كل 3 ثوانٍ أثناء نافذة النشاط — أسرع بكثير من الـ20 ثانية الثابتة القديمة. */
        const val DEFAULT_FAST_INTERVAL_MS = 3_000L

        /** سقف الخمول العادي: دقيقة واحدة كحد أقصى بين فحصين عند عدم وجود أي نشاط. */
        const val DEFAULT_IDLE_CEILING_MS = 60_000L

        /** سقف خمول أعلى عند السحابة الفارغة: 3 دقائق، لأن الفحص المتكرر أقل فائدة هناك. */
        const val DEFAULT_EMPTY_IDLE_CEILING_MS = 180_000L

        const val DEFAULT_BACKOFF_MULTIPLIER = 1.8
        const val DEFAULT_ACTIVE_WINDOW_ROUNDS = 3
    }
}
