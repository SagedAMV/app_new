package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

/**
 * اختبارات معجم أعطال السحابة (JVM خالص، بلا Android).
 *
 * تُغلق العطل المُبلَّغ: «يظهر لي إشارة خطأ باللغة الإنجليزية كأنه كود في التطبيق» — كان
 * كل موضع يكتب error.message ?: "عربي" فيسبق النص الخام دائمًا. الاختبار الأهم هنا ليس
 * مقارنة نصوص واحدة تلو أخرى بل الخاصية: **لا مدخل خام يخرج إلا من المعجم المعتمد**
 * (F1/F4/F5/F6/F7/F9 من مصفوفة السيناريوهات، وI1/I2، وM1/M2).
 */
class CloudFailureMessagesTest {

    /** رسائل حقيقية من طبقات الشبكة/التخزين على أندرويد، كما تصل من OkHttp وUnixStreams */
    private val realWorldRawMessages = listOf(
        "Unable to resolve host \"api.r2.dev\": No address associated with hostname",
        "Read timed out",
        "unexpected end of stream on com.android.okhttp.Address@1a2b3c4d",
        "Connection reset",
        "Broken pipe (Write failed)",
        "Software caused connection abort: conn failed",
        "Cleartext HTTP traffic to 192.168.1.5 not permitted",
        "java.net.SocketTimeoutException: timeout",
        "Received HTTP code 407 from proxy after CONNECT",
        "Trust anchor for certification path not found",
        "/storage/emulated/0/Download/x.pdf: open failed: ENOENT (No such file or directory)",
        "ENOSPC (No space left on device)",
        "EACCES (Permission denied)",
        "Failed to connect to api.r2.dev/104.16.1.1 after 15000ms: isConnected failed: ECONNABORTED (Operation aborted)",
        "HTTP 404 Not Found",
        "Timeout expired. The timeout period elapsed prior to completion of the operation",
        "Retrofit call failed with code 503 Service Unavailable",
        "SignalException: connection was closed gracefully",
        "ENOTCONN (Transport endpoint is not connected)",
        "null"
    )

    @Test fun noRawNetworkMessageEverReachesTheUser() {
        for (message in realWorldRawMessages) {
            for (error in listOf(IOException(message), RuntimeException(message), IllegalStateException(message))) {
                val out = CloudFailureMessages.userMessage(error)
                assertTrue("تسريب نص تقني («$message»): $out", out in CloudFailureMessages.USER_FACING)
            }
        }
    }

    @Test fun everyUserTextIsArabicAndTechnicalFree() {
        CloudFailureMessages.USER_FACING.forEach {
            assertTrue("نص بلا عربية: $it", CloudFailureMessages.isUserFacing(it))
            assertFalse("رقم حالة في نص المستخدم: $it", Regex("""\d{3}""").containsMatchIn(it))
        }
    }

    @Test fun unknownHostIsExplainedNotDumped() {
        val error = UnknownHostException("Unable to resolve host \"api.r2.dev\": No address associated with hostname")
        assertEquals(CloudFailureMessages.UNREACHABLE, CloudFailureMessages.userMessage(error))
    }

    @Test fun readTimeoutIsTimeoutReason() {
        assertEquals(CloudFailureMessages.TIMEOUT, CloudFailureMessages.userMessage(SocketTimeoutException("Read timed out")))
    }

    @Test fun droppedStreamIsToldAsDisconnect() {
        assertEquals(CloudFailureMessages.DROPPED, CloudFailureMessages.userMessage(IOException("unexpected end of stream")))
        assertEquals(CloudFailureMessages.DROPPED, CloudFailureMessages.userMessage(ConnectException("Connection refused")))
    }

    @Test fun offlineOverridesAnyNetworkFault() {
        // المستخدم لا يحتاج «انتهت مهلة» بل «لا إنترنت» — والدفعة محفوظة وستعود
        val out = CloudFailureMessages.userMessage(SocketTimeoutException("Read timed out"), networkAvailable = false)
        assertEquals(CloudFailureMessages.OFFLINE, out)
        assertEquals(CloudFailureMessages.OFFLINE, CloudFailureMessages.userMessage(UnknownHostException("nope"), networkAvailable = false))
    }

    @Test fun noRouteIsAlwaysOffline() {
        assertEquals(CloudFailureMessages.OFFLINE, CloudFailureMessages.userMessage(NoRouteToHostException("Network is unreachable")))
    }

    @Test fun tlsFailureIsSecureConnectionReason() {
        assertEquals(CloudFailureMessages.TLS, CloudFailureMessages.userMessage(SSLHandshakeException("Certificate pinning failure")))
    }

    @Test fun missingLocalFileSaysSoInArabic() {
        val error = FileNotFoundException("/storage/emulated/0/x.txt: open failed: ENOENT (No such file or directory)")
        assertEquals(CloudFailureMessages.MISSING_LOCAL, CloudFailureMessages.userMessage(error))
    }

    @Test fun userPauseIsNeverAnError() {
        // CancellationException من زر الإيقاف أو من إنهاء النظام للعامل: نص هادئ، لا «خطأ»
        assertEquals(CloudFailureMessages.INTERRUPTED, CloudFailureMessages.userMessage(CancellationException("Job was cancelled")))
    }

    @Test fun arabicProjectMessagesAreKeptVerbatim() {
        assertEquals("اسم الملف لا يجوز أن يكون فارغًا", CloudFailureMessages.userMessage(IllegalStateException("اسم الملف لا يجوز أن يكون فارغًا")))
        val conflict = CloudConflictException()
        assertEquals(conflict.message, CloudFailureMessages.userMessage(conflict))
    }

    @Test fun mixedArabicAndTechnicalIsRejectedNotKept() {
        // F9: رسالة «عربية» تحمل اسم API — تمرّ على فحص «فيه عربية» وحده، فصار الفحص مزدوجًا
        val out = CloudFailureMessages.userMessage(IOException("تعذّر Unable to resolve host"))
        assertEquals(CloudFailureMessages.UNREACHABLE, out)
        assertFalse(CloudFailureMessages.isUserFacing("تعذّر Unable to resolve host"))
    }

    @Test fun nullThrowableAndNullMessageNeverPrintNull() {
        assertEquals(CloudFailureMessages.GENERIC, CloudFailureMessages.userMessage(null))
        assertEquals(CloudFailureMessages.GENERIC, CloudFailureMessages.userMessage(NullPointerException()))
        assertFalse(CloudFailureMessages.userMessage(NullPointerException()).contains("null"))
    }

    @Test fun sameErrorAlwaysGivesSameText() {
        // M1 (determinism): نفس العطل في جولة إعادة المحاولة يجب أن يعطي السطر نفسه في البطاقة
        val error = IOException("Read timed out")
        assertEquals(CloudFailureMessages.userMessage(error), CloudFailureMessages.userMessage(error))
    }

    @Test fun addingTechnicalNoiseDoesNotChangeUserText() {
        // M2: تفاصيل داخلية زائدة لا تُغيّر ما يراه المستخدم ولا تُسرَّب إليه
        val clean = CloudFailureMessages.userMessage(IOException("Unable to resolve host x"))
        val noisy = CloudFailureMessages.userMessage(IOException("Unable to resolve host x (retries=3, code 500, okhttp3.IOTimeoutException)"))
        assertEquals(clean, noisy)
    }

    @Test fun httpStatusesBecomeHumanReasonsWithoutNumbers() {
        assertEquals(CloudFailureMessages.SESSION, CloudFailureMessages.messageFor(401))
        assertEquals(CloudFailureMessages.SESSION, CloudFailureMessages.messageFor(403))
        assertEquals(CloudFailureMessages.GONE_REMOTE, CloudFailureMessages.messageFor(404))
        assertEquals(CloudFailureMessages.CONFLICT, CloudFailureMessages.messageFor(412))
        assertEquals(CloudFailureMessages.CONFLICT, CloudFailureMessages.messageFor(409))
        assertEquals(CloudFailureMessages.TOO_BIG, CloudFailureMessages.messageFor(413))
        assertEquals(CloudFailureMessages.THROTTLED, CloudFailureMessages.messageFor(429))
        listOf(500, 502, 503, 504, 509).forEach { assertEquals(CloudFailureMessages.SERVER, CloudFailureMessages.messageFor(it)) }
        assertEquals(CloudFailureMessages.GENERIC, CloudFailureMessages.messageFor(302))
        realWorldRawMessages.filter { it.contains("404") || it.contains("503") }.forEach {
            assertTrue(CloudFailureMessages.userMessage(IOException(it)) in CloudFailureMessages.USER_FACING)
        }
    }

    @Test fun orKeepsOnlyUserWrittenText() {
        assertEquals("اسم غير صالح", CloudFailureMessages.or(RuntimeException("IllegalStateException: blank name"), "اسم غير صالح"))
        assertEquals("اسم غير صالح", CloudFailureMessages.or(null, "اسم غير صالح"))
        assertEquals("المسافة غير كافية لهذا الملف", CloudFailureMessages.or(IllegalStateException("المسافة غير كافية لهذا الملف"), "اسم غير صالح"))
    }

    @Test fun isUserFacingRejectsEverythingTechnicalOrEmpty() {
        listOf(null, "", "   ", "HTTP 500", "java.io.IOException: boom", "فشل HTTP 500", "تعذّر Unable to resolve", "خطأ (code 404)")
            .forEach { assertFalse("$it مرفوض", CloudFailureMessages.isUserFacing(it)) }
        assertTrue(CloudFailureMessages.isUserFacing("تعذّر الحذف من السحابة"))
    }
}
