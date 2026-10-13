package com.unihub.app.data.auth

import com.unihub.app.data.appwrite.AppwriteConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudAuthSecurityTest {
    private fun profile(email: String = "student@example.test", labels: List<String> = emptyList()) = JSONObject()
        .put("\$id", "account-1").put("email", email).put("name", "Student").put("status", true)
        .put("labels", JSONArray(labels)).put("\$createdAt", "2026-10-12T00:00:00Z")
        .put("\$updatedAt", "2026-10-12T00:00:00Z")

    @Test fun ownerNameAndEmailNeverGrantAdminRights() {
        val user = CloudAuthRules.fromAppwriteUser(profile("saged@example.test").put("name", "saged").put("isAdmin", true))
        assertFalse(user.isAdmin)
        assertEquals(UserPermissions.NONE, user.effectivePermissions)
    }
    @Test fun editablePreferencesCannotGrantPermissions() {
        val json = profile().put("prefs", JSONObject().put("isAdmin", true).put("canUpload", true))
        assertFalse(CloudAuthRules.fromAppwriteUser(json).isAdmin)
        assertFalse(CloudAuthRules.fromAppwriteUser(json).permissions.canUpload)
    }
    @Test fun adminLabelAloneGrantsAdminAndLegacyAdminLabelDoesNot() {
        val admin = CloudAuthRules.fromAppwriteUser(profile(labels = listOf("admin")))
        assertTrue(admin.isAdmin)
        assertEquals(UserPermissions.FULL, admin.permissions)

        val legacy = CloudAuthRules.fromAppwriteUser(profile(labels = listOf("unihub_admin")))
        assertFalse(legacy.isAdmin)
        assertEquals(UserPermissions.NONE, legacy.effectivePermissions)
    }
    @Test fun onlyManagedServerLabelsGrantPermissions() {
        val user = CloudAuthRules.fromAppwriteUser(profile(labels = listOf(AppwriteConfig.DOWNLOAD_LABEL, "write")))
        assertTrue(user.permissions.canDownload)
        assertFalse(user.permissions.canUpload)
        assertFalse(user.isAdmin)
    }
    @Test fun disabledAdminIsDeniedToo() {
        val user = CloudAuthRules.fromAppwriteUser(profile(labels = listOf(AppwriteConfig.ADMIN_LABEL)).put("status", false))
        assertEquals(UserPermissions.NONE, user.effectivePermissions)
        AuthPermission.entries.forEach { assertTrue(CloudAuthRules.checkPermission(user, it).isFailure) }
    }
    @Test fun stringBooleansDoNotActivateAnAccount() {
        assertFalse(CloudAuthRules.fromAppwriteUser(profile().put("status", "true")).isActive)
    }
    @Test fun oldRegistryIsNotAnAppwriteProfile() {
        assertTrue(CloudAuthRules.fromJson("""{"schemaVersion":3,"users":[{"username":"saged","isAdmin":true}]}""").users.isEmpty())
    }
    @Test fun profileCacheIsBoundToAuthProjectAndContainsNoPasswordFields() {
        val user = CloudAuthRules.fromAppwriteUser(profile(labels = listOf(AppwriteConfig.DOWNLOAD_LABEL)))
        val raw = CloudAuthRules.toJson(CloudAuthRegistry(users = listOf(user)))
        assertFalse(raw.contains("password", ignoreCase = true))
        assertEquals(user, CloudAuthRules.fromJson(raw).users.single())
        val otherProject = JSONObject(raw).put("projectId", AppwriteConfig.STORAGE_PROJECT_ID).toString()
        assertTrue(CloudAuthRules.fromJson(otherProject).users.isEmpty())
    }
    @Test fun malformedCacheFailsClosed() {
        assertTrue(CloudAuthRules.fromJson("not json").users.isEmpty())
        assertTrue(CloudAuthRules.fromJson(null).users.isEmpty())
    }
    @Test fun localProfileCacheContainsOnlyTheCurrentUser() {
        val first = CloudAuthRules.fromAppwriteUser(profile())
        val second = CloudAuthRules.fromAppwriteUser(profile("other@example.test").put("\$id", "account-2"))
        assertEquals(listOf(first), CloudAuthRules.redactedRegistry(CloudAuthRegistry(users = listOf(first, second)), first.username).users)
    }
    @Test fun offlineCacheAgeIsBoundedAndRejectsClockRollback() {
        val now = 1_800_000_000_000L
        assertTrue(CloudAuthRules.isCachedSessionFresh(now, now))
        assertFalse(CloudAuthRules.isCachedSessionFresh(0, now))
        assertFalse(CloudAuthRules.isCachedSessionFresh(now + 1, now))
        assertFalse(CloudAuthRules.isCachedSessionFresh(now, now + CloudAuthRules.MAX_OFFLINE_SESSION_AGE_MS + 1))
    }
}
