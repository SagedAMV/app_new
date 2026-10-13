package io.openruntimes.kotlin

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** No Android dependency. These rules are exercised with a fake API by the portable tests. */
object BridgeConfig {
    const val ENDPOINT = "https://nyc.cloud.appwrite.io/v1"
    const val AUTH_PROJECT = "6acd036f0022a495a9df"
    const val STORAGE_PROJECT = "6acd0a6800189ccc164a"
    const val BUCKET = "6acd11520015520ba016"
    const val TOKEN_SECONDS = 300
    const val ADMIN = "admin"
    const val READ = "unihub_download"
    const val CREATE = "unihub_upload"
    const val MODIFY = "unihub_modify"
    const val MANAGED = "unihub_managed"
    val bucketPermissions = setOf("read(\"label:$READ\")", "create(\"label:$CREATE\")",
        "update(\"label:$MODIFY\")", "delete(\"label:$MODIFY\")")
    fun validId(id: String) = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,35}").matches(id)
    fun validEmail(email: String) = email.length <= 320 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email) && email.none(Char::isISOControl)
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun shadowId(userId: String) = "u" + digest("$AUTH_PROJECT:$userId").take(35)
    fun sourceLabel(userId: String) = "src_" + digest("$AUTH_PROJECT:$userId").take(32)
}

class BridgeFailure(val status: Int, val code: String) : RuntimeException(code)
class UpstreamFailure(val status: Int, val type: String) : RuntimeException("upstream_request_failed")

interface BridgeApi {
    /** Must use X-Appwrite-JWT ONLY, never an admin key, against the AUTH project /account. */
    fun account(jwt: String): JSONObject
    fun auth(method: String, path: String, body: JSONObject? = null, queries: List<String> = emptyList()): JSONObject
    fun storage(method: String, path: String, body: JSONObject? = null): JSONObject
}

class BridgeService(private val api: BridgeApi) {
    fun execute(jwt: String, input: JSONObject): JSONObject {
        if (jwt.length !in 16..8192 || !Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").matches(jwt)) {
            throw BridgeFailure(401, "unauthorized")
        }
        val actor = api.account(jwt) // Cryptographic/session verification happens in Appwrite, not in JSON decoding.
        requireActiveActor(actor)
        val operation = input.optString("operation")
        val fields = when (operation) {
            "storageToken" -> setOf("operation")
            "listUsers" -> setOf("operation", "cursor")
            "createUser" -> setOf("operation", "email", "password", "permissions")
            "deleteUser", "revokeSessions" -> setOf("operation", "userId")
            "setUserActive" -> setOf("operation", "userId", "active")
            "setPermissions" -> setOf("operation", "userId", "permissions")
            "setPassword" -> setOf("operation", "userId", "password")
            else -> throw BridgeFailure(400, "invalid_operation")
        }
        if (input.keySet().any { it !in fields }) throw BridgeFailure(400, "invalid_request")
        if (operation == "storageToken") return storageToken(actor, jwt)
        if (BridgeConfig.ADMIN !in labels(actor)) throw BridgeFailure(403, "forbidden")
        return when (operation) {
            "listUsers" -> listUsers(input)
            "createUser" -> createUser(input)
            else -> mutateUser(operation, actor, input)
        }
    }

    private fun storageToken(actor: JSONObject, jwt: String): JSONObject {
        verifyBucketPolicy()
        val userId = actor.getString("\$id")
        val effective = effectiveLabels(actor)
        if (effective.isEmpty()) throw BridgeFailure(403, "forbidden")
        val shadow = syncShadow(actor, create = true) ?: throw BridgeFailure(503, "bridge_unavailable")
        // Verify again after provisioning, not just before potentially slow cross-project calls.
        val latest = api.account(jwt)
        requireActiveActor(latest)
        if (latest.getString("\$id") != userId || effectiveLabels(latest) != effective) {
            denyShadow(userId)
            throw BridgeFailure(403, "forbidden")
        }
        val token = api.storage("POST", "/users/$shadow/jwts", JSONObject().put("duration", BridgeConfig.TOKEN_SECONDS))
        return JSONObject().put("projectId", BridgeConfig.STORAGE_PROJECT).put("bucketId", BridgeConfig.BUCKET)
            .put("authUserId", userId).put("jwt", token.getString("jwt")).put("expiresIn", BridgeConfig.TOKEN_SECONDS)
    }

    private fun verifyBucketPolicy() {
        val bucket = api.storage("GET", "/storage/buckets/${BridgeConfig.BUCKET}")
        if (bucket.optString("\$id") != BridgeConfig.BUCKET || bucket.opt("enabled") != true ||
            bucket.opt("fileSecurity") != false || stringSet(bucket.optJSONArray("\$permissions")) != BridgeConfig.bucketPermissions) {
            throw BridgeFailure(503, "bucket_policy")
        }
    }

    private fun syncShadow(user: JSONObject, create: Boolean): String? {
        val userId = user.getString("\$id")
        val id = BridgeConfig.shadowId(userId)
        var shadow = shadowOrNull(userId)
        if (shadow == null) {
            if (!create) return null
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })
            try {
                // Technical identity only: no copy of the login email, phone, or user's password.
                api.storage("POST", "/users", JSONObject().put("userId", id).put("password", password).put("name", "UniHub storage"))
                api.storage("PUT", "/users/$id/labels", JSONObject().put("labels", JSONArray(listOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel(userId)))))
                shadow = shadowOrNull(userId)
            } catch (error: UpstreamFailure) {
                if (error.status != 409) throw error
                shadow = shadowOrNull(userId)
            }
        }
        if (shadow == null) throw BridgeFailure(503, "bridge_unavailable")
        val desired = if (user.opt("status") == true) effectiveLabels(user) else emptySet()
        val allLabels = setOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel(userId)) + desired
        api.storage("PUT", "/users/$id/labels", JSONObject().put("labels", JSONArray(allLabels)))
        api.storage("PATCH", "/users/$id/status", JSONObject().put("status", user.opt("status") == true && desired.isNotEmpty()))
        return id
    }

    /** Refuse to take over an existing storage identity not created/marked by this bridge. */
    private fun shadowOrNull(userId: String): JSONObject? {
        val id = BridgeConfig.shadowId(userId)
        val user = try { api.storage("GET", "/users/$id") }
        catch (error: UpstreamFailure) { if (error.status == 404 && error.type == "user_not_found") return null else throw error }
        val identityLabels = setOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel(userId))
        if (user.optString("\$id") != id || !labels(user).containsAll(identityLabels)) {
            throw BridgeFailure(409, "conflict")
        }
        if (user.optString("email").isNotBlank() || user.optString("phone").isNotBlank()) {
            // A technical identity must never become an independently sign-in-capable account.
            api.storage("PATCH", "/users/$id/status", JSONObject().put("status", false))
            api.storage("PUT", "/users/$id/labels", JSONObject().put("labels", JSONArray(identityLabels)))
            throw BridgeFailure(409, "conflict")
        }
        return user
    }

    private fun denyShadow(userId: String) {
        if (shadowOrNull(userId) == null) return
        val id = BridgeConfig.shadowId(userId)
        api.storage("PATCH", "/users/$id/status", JSONObject().put("status", false))
        api.storage("PUT", "/users/$id/labels", JSONObject().put("labels", JSONArray(listOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel(userId)))))
    }

    private fun listUsers(input: JSONObject): JSONObject {
        val cursor = input.optString("cursor")
        if (cursor.isNotEmpty() && !BridgeConfig.validId(cursor)) throw BridgeFailure(400, "invalid_request")
        val queries = mutableListOf(query("limit", values = listOf(100)), query("orderAsc", "\$id"))
        if (cursor.isNotEmpty()) queries += query("cursorAfter", values = listOf(cursor))
        val page = api.auth("GET", "/users", queries = queries).getJSONArray("users")
        val visible = JSONArray()
        for (i in 0 until page.length()) {
            val user = page.getJSONObject(i)
            if (BridgeConfig.validEmail(user.optString("email"))) visible.put(publicProfile(user))
        }
        return JSONObject().put("users", visible).put("cursor", if (page.length() == 100) page.getJSONObject(99).getString("\$id") else "")
    }

    private fun createUser(input: JSONObject): JSONObject {
        val email = input.getString("email").trim().lowercase(java.util.Locale.ROOT)
        if (!BridgeConfig.validEmail(email)) throw BridgeFailure(400, "invalid_email")
        val password = password(input)
        val desired = permissionLabels(input.getJSONObject("permissions"))
        val id = UUID.randomUUID().toString()
        api.auth("POST", "/users", JSONObject().put("userId", id).put("email", email)
            .put("password", password).put("name", email.substringBefore('@').take(128)))
        // If setting labels fails, the new account has NO storage permissions; there is no privileged default.
        val user = api.auth("PUT", "/users/$id/labels", JSONObject().put("labels", JSONArray(desired)))
        return JSONObject().put("user", publicProfile(user))
    }

    private fun mutateUser(operation: String, actor: JSONObject, input: JSONObject): JSONObject {
        val id = input.getString("userId")
        if (!BridgeConfig.validId(id)) throw BridgeFailure(400, "invalid_request")
        val target = api.auth("GET", "/users/$id")
        if (id == actor.getString("\$id") || BridgeConfig.ADMIN in labels(target)) throw BridgeFailure(403, "protected_admin")
        // Validate before making any changes, then revoke storage access first (fail closed on partial failures).
        val desired = if (operation == "setPermissions") permissionLabels(input.getJSONObject("permissions")) else null
        val newPassword = if (operation == "setPassword") password(input) else null
        if (operation == "setUserActive" && input.opt("active") !is Boolean) throw BridgeFailure(400, "invalid_request")
        denyShadow(id)
        when (operation) {
            "deleteUser" -> {
                api.auth("DELETE", "/users/$id")
                // Retain the disabled technical identity as a tombstone. No old JWT can regain access.
                return JSONObject().put("ok", true)
            }
            "setUserActive" -> api.auth("PATCH", "/users/$id/status", JSONObject().put("status", input.getBoolean("active")))
            "setPermissions" -> {
                val preserved = labels(target) - setOf(BridgeConfig.READ, BridgeConfig.CREATE, BridgeConfig.MODIFY)
                api.auth("PUT", "/users/$id/labels", JSONObject().put("labels", JSONArray(preserved + desired.orEmpty())))
            }
            "setPassword" -> {
                api.auth("PATCH", "/users/$id/password", JSONObject().put("password", newPassword))
                api.auth("DELETE", "/users/$id/sessions")
            }
            "revokeSessions" -> api.auth("DELETE", "/users/$id/sessions")
        }
        if (operation != "revokeSessions" && operation != "setPassword") {
            syncShadow(api.auth("GET", "/users/$id"), create = false)
        }
        return JSONObject().put("ok", true)
    }

    private fun requireActiveActor(actor: JSONObject) {
        if (actor.opt("status") != true || !BridgeConfig.validId(actor.optString("\$id")) ||
            !BridgeConfig.validEmail(actor.optString("email"))) throw BridgeFailure(401, "unauthorized")
    }
    private fun effectiveLabels(user: JSONObject): Set<String> {
        val assigned = labels(user)
        if (BridgeConfig.ADMIN in assigned) return setOf(BridgeConfig.READ, BridgeConfig.CREATE, BridgeConfig.MODIFY)
        val effective = assigned.intersect(setOf(BridgeConfig.READ, BridgeConfig.CREATE, BridgeConfig.MODIFY))
        checkHierarchy(effective)
        return effective
    }
    private fun permissionLabels(json: JSONObject): Set<String> {
        if (json.keySet() != setOf("canDownload", "canUpload", "canModify") || json.keySet().any { json.opt(it) !is Boolean }) {
            throw BridgeFailure(400, "invalid_permissions")
        }
        val result = buildSet {
            if (json.getBoolean("canDownload")) add(BridgeConfig.READ)
            if (json.getBoolean("canUpload")) add(BridgeConfig.CREATE)
            if (json.getBoolean("canModify")) add(BridgeConfig.MODIFY)
        }
        checkHierarchy(result)
        return result
    }
    private fun checkHierarchy(labels: Set<String>) {
        if ((BridgeConfig.CREATE in labels && BridgeConfig.READ !in labels) ||
            (BridgeConfig.MODIFY in labels && (BridgeConfig.READ !in labels || BridgeConfig.CREATE !in labels))) {
            throw BridgeFailure(400, "invalid_permissions")
        }
    }
    private fun password(input: JSONObject): String = input.getString("password").also {
        if (it.length !in 10..256 || it.isBlank()) throw BridgeFailure(400, "invalid_password")
    }
    private fun publicProfile(user: JSONObject): JSONObject = JSONObject().apply {
        // Users API responses can contain password hashes: only these fields may leave the server.
        for (key in listOf("\$id", "email", "name", "status", "labels", "\$createdAt", "\$updatedAt")) put(key, user.get(key))
    }
    private fun labels(user: JSONObject) = stringSet(user.optJSONArray("labels"))
    private fun stringSet(array: JSONArray?): Set<String> = if (array == null) emptySet() else
        (0 until array.length()).mapNotNull { array.opt(it) as? String }.toSet()
    private fun query(method: String, attribute: String? = null, values: List<Any> = emptyList()) =
        JSONObject().put("method", method).put("values", JSONArray(values)).apply { attribute?.let { put("attribute", it) } }.toString()
}
