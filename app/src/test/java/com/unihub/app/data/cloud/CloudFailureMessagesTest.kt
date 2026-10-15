package com.unihub.app.data.cloud

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudFailureMessagesTest {
    @Test
    fun unauthorizedMeansExpiredSessionButForbiddenMeansMissingPermission() {
        assertEquals(CloudFailureMessages.SESSION, CloudFailureMessages.messageFor(401))
        assertEquals(CloudFailureMessages.PERMISSION_DENIED, CloudFailureMessages.messageFor(403))
    }

    @Test
    fun statusMessagesNeverExposeRawHttpDetails() {
        listOf(401, 403, 404, 409, 413, 429, 503).forEach { status ->
            val message = CloudFailureMessages.messageFor(status)
            assertEquals("message for $status should be user-facing", true, CloudFailureMessages.isUserFacing(message))
            assertEquals("message for $status should not expose status numbers", false, message.contains(status.toString()))
        }
    }

    // ─── ما حُذف مع refactor Appwrite: الخاصية التي تمنع رجوع العطل المُبلَّغ ───

    /** رسائل واقعية من طبقات الشبكة على أندرويد، كما تصل من OkHttp وHttpURLConnection */
    private val realWorldRawMessages = listOf(
        "Unable to resolve host \"api.example.com\": No address associated with hostname",
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
        "Failed to connect to api.example.com/104.16.1.1 after 15000ms: isConnected failed: ECONNABORTED (Operation aborted)",
        "HTTP 404 Not Found",
        "Timeout expired. The timeout period elapsed prior to completion of the operation",
        "Retrofit call failed with code 503 Service Unavailable",
        "SignalException: connection was closed gracefully",
        "ENOTCONN (Transport endpoint is not connected)",
        "null"
    )

    @Test
    fun noRawNetworkMessageEverReachesTheUser() {
        for (message in realWorldRawMessages) {
            for (error in listOf(IOException(message), RuntimeException(message), IllegalStateException(message))) {
                val out = CloudFailureMessages.userMessage(error)
                assertTrue("تسريب نص تقني («$message»): $out", out in CloudFailureMessages.USER_FACING)
            }
        }
    }

    @Test
    fun everyUserTextIsArabicAndTechnicalFree() {
        CloudFailureMessages.USER_FACING.forEach {
            assertTrue("نص بلا عربية: $it", CloudFailureMessages.isUserFacing(it))
            assertFalse("رقم حالة في نص المستخدم: $it", Regex("""\b\d{3}\b""").containsMatchIn(it))
        }
    }

    @Test fun unknownHostIsExplainedNotDumped() {
        val error = UnknownHostException("Unable to resolve host \"api.example.com\": No address associated with hostname")
        assertEquals(CloudFailureMessages.UNREACHABLE, CloudFailureMessages.userMessage(error))
    }

    @Test fun readTimeoutAndDroppedStreamHaveTheirOwnReasons() {
        assertEquals(CloudFailureMessages.TIMEOUT, CloudFailureMessages.userMessage(SocketTimeoutException("Read timed out")))
        assertEquals(CloudFailureMessages.DROPPED, CloudFailureMessages.userMessage(IOException("unexpected end of stream")))
        assertEquals(CloudFailureMessages.DROPPED, CloudFailureMessages.userMessage(ConnectException("Connection refused")))
    }

    @Test fun offlineOverridesAnyNetworkFault() {
        assertEquals(CloudFailureMessages.OFFLINE, CloudFailureMessages.userMessage(SocketTimeoutException("Read timed out"), networkAvailable = false))
        assertEquals(CloudFailureMessages.OFFLINE, CloudFailureMessages.userMessage(UnknownHostException("nope"), networkAvailable = false))
        assertEquals(CloudFailureMessages.OFFLINE, CloudFailureMessages.userMessage(NoRouteToHostException("Network is unreachable")))
    }

    @Test fun secureChannelAndMissingLocalFileAreToldInArabic() {
        assertEquals(CloudFailureMessages.TLS, CloudFailureMessages.userMessage(SSLHandshakeException("Certificate pinning failure")))
        val missing = FileNotFoundException("/storage/emulated/0/x.txt: open failed: ENOENT (No such file or directory)")
        assertEquals(CloudFailureMessages.MISSING_LOCAL, CloudFailureMessages.userMessage(missing))
    }

    @Test fun userPauseIsNeverAnError() {
        assertEquals(CloudFailureMessages.INTERRUPTED, CloudFailureMessages.userMessage(CancellationException("Job was cancelled")))
    }

    @Test fun arabicProjectMessagesAreKeptVerbatimAndMixedOnesRejected() {
        assertEquals("اسم الملف لا يجوز أن يكون فارغًا", CloudFailureMessages.userMessage(IllegalStateException("اسم الملف لا يجوز أن يكون فارغًا")))
        val conflict = CloudConflictException()
        assertEquals(conflict.message, CloudFailureMessages.userMessage(conflict))
        // «عربية» تحمل اسم API: تمرّ على فحص العربية وحده، لذلك الفحص مزدوج
        assertEquals(CloudFailureMessages.UNREACHABLE, CloudFailureMessages.userMessage(IOException("تعذّر Unable to resolve host")))
        assertFalse(CloudFailureMessages.isUserFacing("تعذّر Unable to resolve host"))
    }

    @Test fun nullThrowableAndNullMessageNeverPrintNull() {
        assertEquals(CloudFailureMessages.GENERIC, CloudFailureMessages.userMessage(null))
        assertEquals(CloudFailureMessages.GENERIC, CloudFailureMessages.userMessage(NullPointerException()))
    }

    @Test fun sameErrorAlwaysGivesSameTextAndNoiseChangesNothing() {
        val error = IOException("Read timed out")
        assertEquals(CloudFailureMessages.userMessage(error), CloudFailureMessages.userMessage(error))
        val clean = CloudFailureMessages.userMessage(IOException("Unable to resolve host x"))
        val noisy = CloudFailureMessages.userMessage(IOException("Unable to resolve host x (retries=3, code 500, okhttp3.IOTimeoutException)"))
        assertEquals(clean, noisy)
    }

    @Test fun httpStatusesBecomeHumanReasonsAnd403IsPermissionNotSession() {
        assertEquals(CloudFailureMessages.SESSION, CloudFailureMessages.messageFor(401))
        assertEquals(CloudFailureMessages.PERMISSION_DENIED, CloudFailureMessages.messageFor(403))
        assertEquals(CloudFailureMessages.GONE_REMOTE, CloudFailureMessages.messageFor(404))
        assertEquals(CloudFailureMessages.CONFLICT, CloudFailureMessages.messageFor(412))
        assertEquals(CloudFailureMessages.TOO_BIG, CloudFailureMessages.messageFor(413))
        assertEquals(CloudFailureMessages.THROTTLED, CloudFailureMessages.messageFor(429))
        listOf(500, 502, 503, 504).forEach { assertEquals(CloudFailureMessages.SERVER, CloudFailureMessages.messageFor(it)) }
        assertEquals(CloudFailureMessages.GENERIC, CloudFailureMessages.messageFor(302))
        realWorldRawMessages.filter { it.contains("404") || it.contains("503") || it.contains("407") }.forEach {
            assertTrue(CloudFailureMessages.userMessage(IOException(it)) in CloudFailureMessages.USER_FACING)
        }
    }

    @Test fun orKeepsOnlyUserWrittenText() {
        assertEquals("اسم غير صالح", CloudFailureMessages.or(RuntimeException("IllegalStateException: blank name"), "اسم غير صالح"))
        assertEquals("اسم غير صالح", CloudFailureMessages.or(null, "اسم غير صالح"))
        assertEquals("المسافة غير كافية لهذا الملف", CloudFailureMessages.or(IllegalStateException("المسافة غير كافية لهذا الملف"), "اسم غير صالح"))
    }

    @Test fun isUserFacingRejectsEverythingTechnicalOrEmpty() {
        listOf(null, "", "   ", "HTTP 500", "java.io.IOException: boom", "فشل HTTP 500", "خطأ (code 404)")
            .forEach { assertFalse("مرفوض: $it", CloudFailureMessages.isUserFacing(it)) }
        assertTrue(CloudFailureMessages.isUserFacing("تعذّر الحذف من السحابة"))
    }
}
