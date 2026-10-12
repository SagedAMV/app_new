package io.openruntimes.kotlin

import org.json.JSONException
import org.json.JSONObject

/** Appwrite Function entrypoint. Deploy in the AUTH project; never enable request/response logging. */
class Index {
    fun main(context: RuntimeContext): RuntimeOutput {
        val headers = mapOf("content-type" to "application/json", "cache-control" to "no-store", "pragma" to "no-cache")
        fun error(status: Int, code: String) = context.res.text(JSONObject().put("code", code).toString(), status, headers)
        return try {
            if (System.getenv("APPWRITE_FUNCTION_PROJECT_ID") != BridgeConfig.AUTH_PROJECT) {
                return error(503, "bridge_configuration")
            }
            if (context.req.method != "POST") return error(405, "invalid_method")
            if (context.req.bodyBinary.size > 16 * 1024) return error(413, "invalid_request")
            val jwt = context.req.headers["x-appwrite-user-jwt"].orEmpty()
            if (jwt.isBlank()) return error(401, "unauthorized")
            val api = AppwriteRestApi(
                // Appwrite supplies a dynamic key with the function's configured auth-project scopes.
                authKey = context.req.headers["x-appwrite-key"].orEmpty(),
                storageKey = System.getenv("STORAGE_API_KEY").orEmpty()
            )
            val result = BridgeService(api).execute(jwt, JSONObject(context.req.bodyText))
            context.res.text(result.toString(), 200, headers)
        } catch (failure: BridgeFailure) {
            error(failure.status, failure.code)
        } catch (failure: UpstreamFailure) {
            when {
                failure.status == 409 -> error(409, "conflict")
                failure.status == 404 && failure.type == "user_not_found" -> error(404, "not_found")
                else -> error(502, "bridge_upstream")
            }
        } catch (_: JSONException) {
            error(400, "invalid_request")
        } catch (_: Exception) {
            // No exception message, body, password, key, or JWT is written to runtime logs.
            error(503, "bridge_unavailable")
        }
    }
}
