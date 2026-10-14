package com.unihub.app.data.appwrite

import com.unihub.app.di.AuthAppwrite
import io.appwrite.enums.ExecutionMethod
import io.appwrite.exceptions.AppwriteException
import io.appwrite.services.Functions
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class AppwriteBridgeException(val status: Int, val errorCode: String) : IOException(
    when (errorCode) {
        "invalid_permissions" -> "الرفع يحتاج إذن القراءة، والتعديل يحتاج إذنَي القراءة والرفع"
        "invalid_email" -> "أدخل بريدًا إلكترونيًا صحيحًا لحساب Appwrite"
        "invalid_password" -> "كلمة المرور يجب أن تكون من 10 إلى 256 حرفًا"
        "protected_admin" -> "لا يمكن تعديل حساب إداري من هذه الواجهة؛ استخدم لوحة Appwrite"
        "bucket_policy" -> "صلاحيات حاوية Appwrite غير مهيأة بأمان؛ راجع دليل إعداد السحابة"
        "unauthorized" -> "انتهت جلسة Appwrite؛ سجّل الدخول مجددًا"
        "forbidden" -> "لا يملك الحساب صلاحية تنفيذ هذا الإجراء"
        "conflict" -> "الحساب موجود مسبقًا أو حدث تعارض؛ حدّث القائمة وأعد المحاولة"
        "not_found" -> "الحساب المطلوب غير موجود"
        "bridge_not_deployed" -> "خدمة unihub-auth-bridge غير منشورة في مشروع المستخدمين على Appwrite؛ انشرها وفعّلها حسب دليل الإعداد"
        else -> "تعذّر تشغيل خدمة ربط Appwrite؛ تحقق من نشر unihub-auth-bridge وإعداداتها"
    }
)

/**
 * Maps an SDK failure of the execution request itself (before the function runs) to a bridge code.
 * Appwrite answers `function_not_found` when the function was never created or was deleted, which
 * must not be confused with a generic runtime failure of a deployed function.
 */
internal fun bridgeErrorCodeFor(status: Int?, type: String?): String = when {
    status == 401 -> "unauthorized"
    status == 403 -> "forbidden"
    status == 404 && type == "function_not_found" -> "bridge_not_deployed"
    else -> "bridge_unavailable"
}

/** Executed exclusively in the Users/Auth project. The function re-verifies the caller's JWT. */
@Singleton
class AppwriteAuthBridge @Inject constructor(
    @AuthAppwrite private val functions: Functions,
    private val sdkGate: AppwriteSdkGate
) {
    suspend fun execute(operation: String, payload: JSONObject = JSONObject(), validateSession: () -> Unit): JSONObject {
        val request = JSONObject(payload.toString()).put("operation", operation).toString()
        require(request.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        val execution = try {
            sdkGate.await {
                validateSession()
                functions.createExecution(
                functionId = AppwriteConfig.AUTH_BRIDGE_FUNCTION_ID,
                body = request,
                async = false,
                method = ExecutionMethod.POST,
                headers = mapOf("content-type" to "application/json")
                )
            }
        } catch (error: AppwriteException) {
            throw AppwriteBridgeException(error.code ?: 503, bridgeErrorCodeFor(error.code, error.type))
        }
        if (execution.responseBody.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
            throw IOException("رد خدمة المستخدمين تجاوز الحد المسموح")
        }
        val result = runCatching { JSONObject(execution.responseBody) }.getOrElse {
            throw AppwriteBridgeException(503, "bridge_unavailable")
        }
        val status = execution.responseStatusCode.toInt()
        if (execution.status != io.appwrite.enums.ExecutionStatus.COMPLETED || status !in 200..299) {
            throw AppwriteBridgeException(status, result.optString("code", "bridge_unavailable"))
        }
        return result
    }
}
