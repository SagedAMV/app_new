package com.unihub.app.data.cloud

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * ترجمة أعطال الشبكة والتخزين إلى كلام مستخدم.
 *
 * قاعدة إلزامية من طبيعة تطبيق.md §5 «لغة التطبيق»: لا تُعرض مصطلحات داخلية (أسماء Classes
 * أو APIs أو حالات تقنية). كان كل موضع يكتب `error.message ?: "عربي"` — أي أن العربية
 * احتياط فقط؛ فمتى كان للاستثناء نصّ (وهو دائمًا الإنجليزية عند سقوط الشبكة) ظهر للمستخدم
 * «UnknownHostException: Unable to resolve host…» في إشعار النقل وبطاقة الطابور.
 * الآن النص الإنساني هو الأصل، والخام يذهب إلى السجلّ وحده.
 *
 * الطبقة نقية بلا Android: تُختبر محليًا (JVM) وتستعملها كل الوجوه معًا — العامل والمدير
 * وفاحص السحابة وشاشتا الملفات والسحابة والمصدّخة — (طبيعة تطبيق.md §11: لا تكرر منطق العمل).
 */
object CloudFailureMessages {

    // ── المعجم المعتمد: كل ما يجوز أن يراه المستخدم عند العطل ──
    const val GENERIC = "تعذّر إتمام العملية؛ تحقّق من الاتصال ثم أعد المحاولة"
    const val OFFLINE = "لا يتوفر إنترنت؛ العملية محفوظة وستُستأنف عند عودة الاتصال"
    const val INTERRUPTED = "أُوقف النقل؛ سيُستأنف من حيث توقف"
    const val UNREACHABLE = "تعذّر الوصول إلى خادم السحابة — تحقّق من الاتصال بالإنترنت"
    const val TIMEOUT = "انتهت مهلة الاتصال بالخادم؛ سيُعاد المحاولة تلقائيًا"
    const val TLS = "تعذّر إتمام اتصال آمن بخادم السحابة؛ تحقّق من بيانات الخادم"
    const val DROPPED = "انقطع الاتصال أثناء النقل؛ ستُعاد المحاولة من حيث توقف"
    const val MISSING_LOCAL = "الملف لم يعد موجودًا على الجهاز"
    const val FOLDER_ACCESS = "تعذّر الوصول إلى المجلد المحفوظ؛ أعد اختياره من الإعدادات"
    const val NO_SPACE = "لا تكفي مساحة التخزين على الجهاز"
    const val SESSION = "انتهت صلاحية الدخول أو لا تملك صلاحية لهذا الإجراء"
    const val GONE_REMOTE = "الملف لم يعد موجودًا على الخادم"
    const val CONFLICT = "تغيّرت البيانات على الخادم؛ أعد الفحص ثم حاول مجددًا"
    const val TOO_BIG = "الملف أكبر من الحد المسموح به على الخادم"
    const val THROTTLED = "عدد محاولات كثير جدًا أو انتهت مهلة الخادم؛ انتظر قليلًا ثم أعد المحاولة"
    const val SERVER = "خادم السحابة لا يستجيب حاليًا؛ ستُعاد المحاولة تلقائيًا"

    /** معجم كامل لا سواه: أي مخرج خارج هذه القائمة عند مدخل خام يُعدّ تسريبًا (اختبار P1) */
    val USER_FACING: Set<String> = setOf(
        GENERIC, OFFLINE, INTERRUPTED, UNREACHABLE, TIMEOUT, TLS, DROPPED, MISSING_LOCAL,
        FOLDER_ACCESS, NO_SPACE, SESSION, GONE_REMOTE, CONFLICT, TOO_BIG, THROTTLED, SERVER
    )

    /** ما يقبله المستخدم نصًا: فيه عربية، وبلا آثار تقنية */
    fun isUserFacing(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (!text.any { it in '؀'..'ۿ' }) return false
        return !containsTechnicalTrace(text)
    }

    /**
     * آثار تقنية لا يجوز أن يراها المستخدم: أسماء أصناف، سلاسل حزم، رموز HTTP وerrno،
     * وعناوين. تُفحص على نصّ الرسالة نفسه: «تعذّر Unable to resolve host» مرفوضة وإن
     * احتوت عربيًا — وإلا مرّر الخادم نصًا مختلطًا إلى الواجهة (سيناريو F9).
     */
    private fun containsTechnicalTrace(text: String): Boolean {
        val lowered = text.lowercase()
        val markers = listOf(
            "exception", "throwable", "java.", "javax.", "kotlin.", "okhttp", "retrofit",
            "http ", "http/", "(http", "http ", "unable to", "failed to", "timed out",
            "connection reset", "connection refused", "broken pipe", "unexpected end of stream",
            "econn", "enetunre", "ehostunre", "eacces", "enoent", "enospc", "epipe", "errno",
            "status code", "response code", "cleartext", "ssl", "tls", "certificate",
            "name or service not known", "network is unreachable", "no route to host",
            "no data of desired type", "stream was reset", "timeout expired", "not permitted",
            "open failed", "at com.", "at java.", "://"
        )
        if (markers.any { lowered.contains(it) }) return true
        // رقم حالة ملاصق لكلمة تقنية: «خطأ (code 404)» عربيّ لكنه تسريب (سيناريو F9)
        if (Regex("""\b\d{3}\b""").containsMatchIn(lowered) &&
            listOf("http", "code", "status", "error", "errno").any { lowered.contains(it) }) return true
        return false
    }

    /**
     * رسالة المستخدم لهذا العطل. [networkAvailable]=false يجعل أي عطل شبكة «لا إنترنت» —
     * لأن «انتهت مهلة» و«لا يوجد إنترنت» سبب مختلف تمامًا لما يفعله المستخدم مع نفسه.
     */
    fun userMessage(error: Throwable?, networkAvailable: Boolean? = null): String {
        if (error == null) return GENERIC
        if (error is CancellationException) return INTERRUPTED
        // رسائل المشروع المكتوبة بالعربية تُحترم كما هي (صيغت بشرح السبب والخطوة التالية)
        error.message?.takeIf { isUserFacing(it) }?.let { return it }

        val raw = buildString {
            append(error.message.orEmpty()).append(' ').append(error::class.java.name.lowercase())
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 4) {
                append(' ').append(cause.message.orEmpty()).append(' ').append(cause::class.java.name.lowercase())
                cause = cause.cause
                depth++
            }
        }.lowercase()

        val offline = networkAvailable == false
        return when {
            error is UnknownHostException || "unable to resolve host" in raw ||
                "name or service not known" in raw || "no address associated" in raw ||
                "no data of desired type" in raw -> if (offline) OFFLINE else UNREACHABLE

            error is NoRouteToHostException || "no route to host" in raw ||
                "network is unreachable" in raw || "enetunre" in raw || "ehostunre" in raw -> OFFLINE

            offline -> OFFLINE

            error is SocketTimeoutException || "timed out" in raw || "timeout" in raw -> TIMEOUT

            error is SSLException || "ssl" in raw || "tls" in raw || "certificate" in raw ||
                "trust anchor" in raw || "cleartext" in raw -> TLS

            error is ConnectException || error is SocketException || "connection reset" in raw ||
                "connection refused" in raw || "broken pipe" in raw || "epipe" in raw ||
                "unexpected end of stream" in raw || "stream was reset" in raw ||
                "connection abort" in raw || "econn" in raw || "aborted" in raw ||
                "not connected" in raw || "socket closed" in raw -> DROPPED

            error is FileNotFoundException || "enoent" in raw || "no such file" in raw ||
                "open failed" in raw -> MISSING_LOCAL

            "eacces" in raw || "permission denied" in raw || "operation not permitted" in raw -> FOLDER_ACCESS
            "enospc" in raw || "no space left" in raw -> NO_SPACE
            "size" in raw && ("exceed" in raw || "too large" in raw || "413" in raw) -> TOO_BIG
            else -> detectStatusCode(raw)?.let { messageFor(it) } ?: GENERIC
        }
    }

    /**
     * احتفاظ برسالة كُتبت أصلًا للمستخدم، وبديل عربي عند أي نص خام: لمواضع التحقق المحلي
     * (أسماء الملفات والصلاحيات) حيث البديل الأدقّ تعرفه الواجهة لا طبقة الشبكة.
     */
    fun or(error: Throwable?, fallback: String): String =
        error?.message?.takeIf { isUserFacing(it) } ?: fallback

    /**
     * سبب إنساني لحالة HTTP — الرقم يبقى في السجلّ ولا يُعرض (طبيعة تطبيق.md §5: لا حالات
     * تقنية). يستعملها عميل R2 عند فشل الطلب، فيصل العطل عربيًا من منبعه كل الطبقات فوقه.
     */
    fun messageFor(status: Int): String = when (status) {
        401, 403 -> SESSION
        404 -> GONE_REMOTE
        409, 412 -> CONFLICT
        413 -> TOO_BIG
        408, 429 -> THROTTLED
        in 500..599 -> SERVER
        else -> GENERIC
    }

    /** رقم حالة مذكور نصًا («HTTP 404»، «status code 503»، «(HTTP 404)») — بلا رقم لا ترجمة */
    private fun detectStatusCode(raw: String): Int? {
        if (!(raw.contains("http") || raw.contains("status") || raw.contains("code") || raw.contains("response"))) return null
        return Regex("""\b([1-5]\d{2})\b""").find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 100..599 }
    }
}
