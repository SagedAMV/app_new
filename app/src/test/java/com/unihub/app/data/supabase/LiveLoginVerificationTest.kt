package com.unihub.app.data.supabase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveLoginVerificationTest {

    @Test
    fun liveLogin_withRealCredentials_returnsActiveAdminAccount() {
        runBlocking {
            val emailName = "UNIHUB_LIVE_" + "EMAIL"
            val passwordName = "UNIHUB_LIVE_" + "PASSWORD"
            val env = System.getenv()
            val email = env[emailName] ?: ""
            val password = env[passwordName] ?: ""
            assumeTrue(email.isNotBlank() && password.isNotBlank())

            val http = SupabaseHttp(SupabaseConfig.PROJECT_URL, SupabaseConfig.PUBLISHABLE_KEY)
            val repository = SupabaseAuthRepository(http)
            val user = repository.login(email, password)

            println(
                "LIVE LOGIN OK: username=" + user.username +
                    " displayName=" + user.displayName +
                    " isAdmin=" + user.isAdmin +
                    " isActive=" + user.isActive +
                    " perms=" + user.effectivePermissions +
                    " userId=" + user.userId
            )
            assertEquals(email.trim().lowercase(), user.username)
            assertTrue(user.isActive)
            assertTrue(repository.currentAccessToken().isNotBlank())

            val restored = repository.currentUser()
            assertEquals(user.userId, restored.userId)
            val users = repository.listUsers()
            println("LIVE ADMIN LIST OK: count=" + users.size)
            assertTrue(users.any { it.userId == user.userId })
        }
    }
}
