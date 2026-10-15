package io.openruntimes.kotlin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeServiceTest {
    @Test fun storageTokenVerifiesAuthTwiceAndUsesDistinctProjectAndBucket() {
        val api = FakeApi()
        val result = BridgeService(api).execute(JWT, request("storageToken"))
        assertEquals(2, api.accountCalls)
        assertEquals(BridgeConfig.STORAGE_PROJECT, result.getString("projectId"))
        assertNotEquals(BridgeConfig.AUTH_PROJECT, result.getString("projectId"))
        assertEquals(BridgeConfig.BUCKET, result.getString("bucketId"))
        assertEquals(300, result.getInt("expiresIn"))
        assertEquals(api.actorId, result.getString("authUserId"))
        assertEquals(setOf("projectId", "bucketId", "authUserId", "jwt", "expiresIn"), result.keySet())
        val shadow = api.storageUsers.values.single()
        assertEquals("", shadow.optString("email"))
        assertTrue(shadow.getString("password").length >= 48)
        assertEquals(setOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel(api.actorId), BridgeConfig.READ, BridgeConfig.CREATE, BridgeConfig.MODIFY), labels(shadow))
        assertEquals(300, api.lastJwtDuration)
    }
    @Test fun invalidOrMissingJwtNeverTouchesStorage() {
        for (token in listOf("", "bad", "different.valid.jwt")) {
            val api = FakeApi()
            denied(401) { BridgeService(api).execute(token, request("storageToken")) }
            assertTrue(api.storageRequests.isEmpty())
        }
    }
    @Test fun callerCannotSupplyAnotherIdentityProjectOrPermissions() {
        for (field in listOf("userId", "actorId", "projectId", "bucketId", "labels", "permissions", "jwt")) {
            val api = FakeApi()
            denied(400) { BridgeService(api).execute(JWT, request("storageToken").put(field, "attacker-value")) }
            assertTrue(api.storageRequests.isEmpty())
        }
    }
    @Test fun adminLabelIsExactAndLegacyLabelCannotManageUsers() {
        val api = FakeApi()
        api.authUsers.getValue(api.actorId).put("labels", JSONArray(listOf("unihub_admin")))
        denied(403) { BridgeService(api).execute(JWT, request("listUsers")) }
        assertTrue(api.authRequests.isEmpty())

        val valid = FakeApi()
        valid.authUsers.getValue(valid.actorId).put("labels", JSONArray(listOf("admin")))
        assertEquals(2, BridgeService(valid).execute(JWT, request("listUsers")).getJSONArray("users").length())
    }
    @Test fun editablePrefsAndDisplayNameDoNotGrantAdministration() {
        val api = FakeApi(actorId = "student")
        api.authUsers.getValue("student").put("name", "saged").put("prefs", JSONObject().put("isAdmin", true))
        denied(403) { BridgeService(api).execute(JWT, request("listUsers")) }
        assertTrue(api.authRequests.isEmpty())
    }
    @Test fun disabledAdminCannotMintOrManage() {
        val api = FakeApi()
        api.authUsers.getValue(api.actorId).put("status", false)
        denied(401) { BridgeService(api).execute(JWT, request("storageToken")) }
        denied(401) { BridgeService(api).execute(JWT, request("listUsers")) }
    }
    @Test fun publicOrPerFileBucketPermissionsFailClosed() {
        for (mutation in listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("\$permissions").put("read(\"any\")") },
            { it.put("fileSecurity", true) },
            { it.put("enabled", false) },
            { it.put("\$id", "wrong-bucket") }
        )) {
            val api = FakeApi(); mutation(api.bucket)
            denied(503, "bucket_policy") { BridgeService(api).execute(JWT, request("storageToken")) }
            assertTrue(api.storageUsers.isEmpty())
            assertEquals(0, api.lastJwtDuration)
        }
    }
    @Test fun readerNeverReceivesUploadOrModifyLabels() {
        val api = FakeApi(actorId = "student")
        BridgeService(api).execute(JWT, request("storageToken"))
        val assigned = labels(api.storageUsers.values.single())
        assertTrue(BridgeConfig.READ in assigned)
        assertFalse(BridgeConfig.CREATE in assigned)
        assertFalse(BridgeConfig.MODIFY in assigned)
    }
    @Test fun permissionsMustBeRealBooleansAndHierarchical() {
        for (permissions in listOf(
            JSONObject().put("canDownload", "true").put("canUpload", false).put("canModify", false),
            permissions(read = false, upload = true),
            permissions(read = true, upload = false, modify = true)
        )) {
            val api = FakeApi()
            denied(400, "invalid_permissions") {
                BridgeService(api).execute(JWT, request("setPermissions").put("userId", "student").put("permissions", permissions))
            }
            assertTrue(api.storageRequests.isEmpty())
        }
    }
    @Test fun unrelatedExistingStorageIdentityCannotBeTakenOver() {
        val api = FakeApi()
        val id = BridgeConfig.shadowId(api.actorId)
        api.storageUsers[id] = user(id, emptyList())
        denied(409) { BridgeService(api).execute(JWT, request("storageToken")) }
        assertEquals(0, api.lastJwtDuration)
        assertTrue(api.storageRequests.none { it.startsWith("PUT") || it.startsWith("PATCH") })
    }
    @Test fun independentlySignInCapableShadowIsDisabledBeforeRefusingTheGrant() {
        val api = FakeApi(actorId = "student")
        api.seedShadow("student")
        val shadow = api.storageUsers.getValue(BridgeConfig.shadowId("student"))
        shadow.put("email", "unexpected@example.test")
        denied(409) { BridgeService(api).execute(JWT, request("storageToken")) }
        assertFalse(shadow.getBoolean("status"))
        assertFalse(BridgeConfig.READ in labels(shadow))
        assertEquals(0, api.lastJwtDuration)
    }
    @Test fun halfProvisionedShadowIsAdoptedInsteadOfLockingTheUserOut() {
        val api = FakeApi(actorId = "student")
        val id = BridgeConfig.shadowId("student")
        api.storageUsers[id] = technicalUser(id, emptyList())
        val result = BridgeService(api).execute(JWT, request("storageToken"))
        assertEquals("student", result.getString("authUserId"))
        val shadow = api.storageUsers.getValue(id)
        assertEquals(setOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel("student"), BridgeConfig.READ), labels(shadow))
        assertTrue(shadow.getBoolean("status"))
        assertTrue(api.lastJwtDuration > 0)
    }
    @Test fun legacyLabelSchemeIsRepairedOnNextStorageToken() {
        val api = FakeApi(actorId = "student")
        val id = BridgeConfig.shadowId("student")
        api.storageUsers[id] = technicalUser(id, listOf("unihub_managed", "unihub_download"))
        BridgeService(api).execute(JWT, request("storageToken"))
        val shadow = api.storageUsers.getValue(id)
        assertTrue(BridgeConfig.MANAGED in labels(shadow))
        assertTrue(BridgeConfig.sourceLabel("student") in labels(shadow))
        assertFalse("unihub_managed" in labels(shadow))
        assertFalse("unihub_download" in labels(shadow))
    }
    @Test fun shadowBoundToAnotherSourceIsStillRefused() {
        val api = FakeApi(actorId = "student")
        val id = BridgeConfig.shadowId("student")
        api.storageUsers[id] = technicalUser(id, listOf(BridgeConfig.MANAGED, "src" + "0".repeat(33)))
        denied(409) { BridgeService(api).execute(JWT, request("storageToken")) }
        assertEquals(0, api.lastJwtDuration)
        assertTrue(api.storageRequests.none { it.startsWith("PUT") || it.startsWith("PATCH") })
    }
    @Test fun listingUsersStripsPasswordsHashesAndPreferences() {
        val api = FakeApi()
        api.authUsers.values.forEach { it.put("password", "secret-verifier").put("hash", "server-hash").put("prefs", JSONObject().put("secret", "hidden")) }
        val result = BridgeService(api).execute(JWT, request("listUsers"))
        assertEquals(2, result.getJSONArray("users").length())
        assertFalse(result.toString().contains("secret-verifier"))
        assertFalse(result.toString().contains("server-hash"))
        assertFalse(result.toString().contains("hidden"))
        assertEquals("", result.getString("cursor"))
    }
    @Test fun existingAdminsAndActorCannotBeModifiedOrDeleted() {
        val api = FakeApi()
        for (operation in listOf("deleteUser", "setPassword", "revokeSessions")) {
            denied(403, "protected_admin") { BridgeService(api).execute(JWT, request(operation).put("userId", api.actorId)) }
        }
        assertTrue(api.storageRequests.isEmpty())
    }
    @Test fun storageIsRevokedBeforeAnAuthMutationAndStaysRevokedOnFailure() {
        val api = FakeApi()
        api.seedShadow("student")
        api.failAuthWrites = true
        try {
            BridgeService(api).execute(JWT, request("setUserActive").put("userId", "student").put("active", false))
            fail("expected upstream failure")
        } catch (_: UpstreamFailure) { }
        val shadow = api.storageUsers.getValue(BridgeConfig.shadowId("student"))
        assertFalse(shadow.getBoolean("status"))
        assertFalse(BridgeConfig.READ in labels(shadow))
    }
    @Test fun permissionChangesMirrorOnlyManagedLabels() {
        val api = FakeApi(); api.seedShadow("student")
        api.authUsers.getValue("student").getJSONArray("labels").put("unrelated_label")
        BridgeService(api).execute(JWT, request("setPermissions").put("userId", "student")
            .put("permissions", permissions(read = true, upload = true)))
        assertTrue("unrelated_label" in labels(api.authUsers.getValue("student")))
        val shadow = api.storageUsers.getValue(BridgeConfig.shadowId("student"))
        assertTrue(BridgeConfig.CREATE in labels(shadow))
        assertFalse("unrelated_label" in labels(shadow))
    }
    @Test fun revokedSessionsLeaveStorageDisabledUntilFreshAuthentication() {
        val api = FakeApi(); api.seedShadow("student")
        BridgeService(api).execute(JWT, request("revokeSessions").put("userId", "student"))
        assertTrue("DELETE /users/student/sessions" in api.authRequests)
        assertFalse(api.storageUsers.getValue(BridgeConfig.shadowId("student")).getBoolean("status"))
    }
    @Test fun accountWithoutAnyFilePermissionsCannotProvisionStorage() {
        val api = FakeApi(actorId = "student")
        api.authUsers.getValue("student").put("labels", JSONArray())
        denied(403) { BridgeService(api).execute(JWT, request("storageToken")) }
        assertTrue(api.storageUsers.isEmpty())
    }
    @Test fun createsRealAuthAccountWithNoStorageCopyOfEmailOrPassword() {
        val api = FakeApi()
        BridgeService(api).execute(JWT, request("createUser").put("email", "new@example.test")
            .put("password", "  exact new password  ").put("permissions", permissions(read = true)))
        val created = api.authUsers.values.single { it.optString("email") == "new@example.test" }
        assertEquals("  exact new password  ", created.getString("password"))
        assertEquals(setOf(BridgeConfig.READ), labels(created))
        assertTrue(api.storageUsers.isEmpty())
    }

    private fun denied(status: Int, code: String? = null, block: () -> Unit) {
        try { block(); fail("Expected denial") }
        catch (failure: BridgeFailure) { assertEquals(status, failure.status); if (code != null) assertEquals(code, failure.code) }
    }

    private class FakeApi(val actorId: String = "admin") : BridgeApi {
        val authUsers = linkedMapOf("admin" to user("admin", listOf(BridgeConfig.ADMIN)), "student" to user("student", listOf(BridgeConfig.READ)))
        val storageUsers = linkedMapOf<String, JSONObject>()
        val authRequests = arrayListOf<String>()
        val storageRequests = arrayListOf<String>()
        var accountCalls = 0
        var lastJwtDuration = 0
        var failAuthWrites = false
        val bucket = JSONObject().put("\$id", BridgeConfig.BUCKET).put("enabled", true).put("fileSecurity", false)
            .put("\$permissions", JSONArray(BridgeConfig.bucketPermissions))
        override fun account(jwt: String): JSONObject {
            accountCalls++
            if (jwt != JWT) throw BridgeFailure(401, "unauthorized")
            return copy(authUsers.getValue(actorId))
        }
        override fun auth(method: String, path: String, body: JSONObject?, queries: List<String>): JSONObject {
            authRequests += "$method $path"
            if (failAuthWrites && method != "GET") throw UpstreamFailure(503, "unavailable")
            if (path == "/users" && method == "GET") return JSONObject().put("users", JSONArray(authUsers.values.toList()))
            return users(authUsers, method, path, body)
        }
        override fun storage(method: String, path: String, body: JSONObject?): JSONObject {
            storageRequests += "$method $path"
            if (path.startsWith("/storage/buckets/")) return copy(bucket)
            if (path.endsWith("/jwts")) {
                lastJwtDuration = body!!.getInt("duration")
                return JSONObject().put("jwt", "storage.project.signature")
            }
            return users(storageUsers, method, path, body)
        }
        fun seedShadow(id: String) {
            val shadow = BridgeConfig.shadowId(id)
            storageUsers[shadow] = user(shadow, listOf(BridgeConfig.MANAGED, BridgeConfig.sourceLabel(id), BridgeConfig.READ)).put("email", "")
        }
        private fun users(records: MutableMap<String, JSONObject>, method: String, path: String, body: JSONObject?): JSONObject {
            if (path == "/users" && method == "POST") {
                val id = body!!.getString("userId")
                if (records.containsKey(id)) throw UpstreamFailure(409, "user_already_exists")
                val row = user(id, emptyList()).put("email", body.optString("email")).put("password", body.getString("password"))
                records[id] = row
                return copy(row)
            }
            val parts = path.split('/')
            val id = parts[2]
            val row = records[id] ?: throw UpstreamFailure(404, "user_not_found")
            if (method == "DELETE") {
                if (parts.size == 3) records.remove(id)
                return JSONObject()
            }
            if (method == "PUT" || method == "PATCH") when (parts.last()) {
                "labels" -> row.put("labels", body!!.getJSONArray("labels"))
                "status" -> row.put("status", body!!.getBoolean("status"))
                "password" -> row.put("password", body!!.getString("password"))
            }
            return copy(row)
        }
    }
    companion object {
        private const val JWT = "valid.auth.signature"
        private fun request(operation: String) = JSONObject().put("operation", operation)
        private fun permissions(read: Boolean = false, upload: Boolean = false, modify: Boolean = false) =
            JSONObject().put("canDownload", read).put("canUpload", upload).put("canModify", modify)
        private fun labels(user: JSONObject): Set<String> = user.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        private fun copy(json: JSONObject) = JSONObject(json.toString())
        /** A bridge-created technical identity: never sign-in capable (no email, no phone). */
        private fun technicalUser(id: String, labels: List<String>) = JSONObject().put("\$id", id).put("email", "")
            .put("phone", "").put("name", "UniHub storage").put("status", true).put("labels", JSONArray(labels))
            .put("\$createdAt", "2026-10-12T00:00:00Z").put("\$updatedAt", "2026-10-12T00:00:00Z")
        private fun user(id: String, labels: List<String>) = JSONObject().put("\$id", id).put("email", "$id@example.test")
            .put("name", id).put("status", true).put("labels", JSONArray(labels))
            .put("\$createdAt", "2026-10-12T00:00:00Z").put("\$updatedAt", "2026-10-12T00:00:00Z")
    }
}
