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
        else -> "تعذّر تشغيل خدمة ربط Appwrite؛ تحقق من نشر unihub-auth-bridge وإعداداتها"
    }
)

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
            throw AppwriteBridgeException(error.code ?: 503,
                if (error.code == 401) "unauthorized" else if (error.code == 403) "forbidden" else "bridge_unavailable")
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
