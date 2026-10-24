package com.unihub.app.data.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اخ اشتقاق الحساب والصلاحيات من صف `public.profiles`.
 *
 * المرجع: قواعد التسلسل نفسها في القاعدة
 * (`public.effective_can_*` و`admin_set_permissions`): تعديل⇒رفع⇒قراءة.
 */
class CloudAuthRulesTest {

    private fun profile(
        id: String = "11111111-1111-1111-1111-111111111111",
        email: String = "User@Example.COM",
        admin: Boolean = false,
        active: Boolean = true,
        download: Boolean = false,
        upload: Boolean = false,
        modify: Boolean = false
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("email", email)
        .put("display_name", "مستخدم")
        .put("is_admin", admin)
        .put("is_active", active)
        .put("can_download", download)
        .put("can_upload", upload)
        .put("can_modify", modify)
        .put("created_at", "2026-10-01T10:00:00+00:00")
        .put("updated_at", "2026-10-02T10:00:00+00:00")

    @Test
    fun adminProfileGetsFullPermissions() {
        val user = CloudAuthRules.fromSupabaseProfile(profile(admin = true))
        assertTrue(user.isAdmin)
        assertEquals(UserPermissions.FULL, user.permissions)
        assertEquals(UserPermissions.FULL, user.effectivePermissions)
    }

    @Test
    fun inactiveAccountHasNoEffectivePermissions() {
        val user = CloudAuthRules.fromSupabaseProfile(
            profile(active = false, download = true, upload = true, modify = true)
        )
        assertFalse(user.isActive)
        assertEquals(UserPermissions.NONE, user.effectivePermissions)
    }

    @Test
    fun permissionChainIsEnforcedWhenMappingRows() {
        val full = CloudAuthRules.fromSupabaseProfile(
            profile(download = true, upload = true, modify = true)
        )
        assertEquals(UserPermissions.FULL, full.permissions)

        // صف يخالف تسلسل الصلاحيات (تعديل بلا قراءة) لا يمنح أي صلاحية إضافية.
        val broken = CloudAuthRules.fromSupabaseProfile(profile(modify = true))
        assertFalse(broken.permissions.canModify)
        assertFalse(broken.permissions.canUpload)
        assertFalse(broken.permissions.canDownload)
    }

    @Test
    fun emailIsNormalizedAndValidated() {
        val user = CloudAuthRules.fromSupabaseProfile(profile(email = "  USER@Example.COM  "))
        assertEquals("user@example.com", user.username)
        assertTrue(CloudAuthRules.isValidEmail("a@b.co"))
        assertFalse(CloudAuthRules.isValidEmail("a b@c.co"))
        assertFalse(CloudAuthRules.isValidEmail("nope"))
        assertFalse(CloudAuthRules.isValidEmail(""))
    }

    @Test
    fun registryCacheRoundTripsAndRejectsForeignSchema() {
        val user = CloudAuthRules.fromSupabaseProfile(profile(download = true, upload = true))
        val json = CloudAuthRules.toJson(CloudAuthRegistry(users = listOf(user)))
        val restored = CloudAuthRules.fromJson(json)
        assertEquals(1, restored.users.size)
        assertEquals(user.userId, restored.users.first().userId)
        assertEquals(user.permissions, restored.users.first().permissions)

        // ذاكرة من مزوّد أقدم (مخطط 4) لا تُقبل كجلسة سحابية.
        val legacy = JSONObject(json).put("schemaVersion", 4).toString()
        assertTrue(CloudAuthRules.fromJson(legacy).users.isEmpty())
    }

    @Test
    fun permissionChecksFollowAccountState() {
        val user = CloudAuthRules.fromSupabaseProfile(profile(download = true))
        assertTrue(CloudAuthRules.checkPermission(user, AuthPermission.DOWNLOAD).isSuccess)
        assertTrue(CloudAuthRules.checkPermission(user, AuthPermission.UPLOAD).isFailure)
        assertTrue(CloudAuthRules.checkPermission(null, AuthPermission.DOWNLOAD).isFailure)
    }
}
