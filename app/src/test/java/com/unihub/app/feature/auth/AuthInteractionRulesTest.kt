package com.unihub.app.feature.auth

import com.unihub.app.data.auth.CloudAuthRules
import org.junit.Assert.*
import org.junit.Test

class AuthInteractionRulesTest {
    @Test fun emailAndPasswordAreRequired() {
        assertNotNull(AuthInteractionRules.inputError("", "password"))
        assertNotNull(AuthInteractionRules.inputError("alice@example.test", ""))
        assertNotNull(AuthInteractionRules.inputError("alice@example.test", "   "))
    }
    @Test fun localUsernameIsNotAnAppwriteEmail() {
        for (email in listOf("saged", "alice@", "@example.test", "alice @example.test", "alice\n@example.test")) {
            assertNotNull(email, AuthInteractionRules.inputError(email, "valid password"))
        }
    }
    @Test fun validEmailAndExactPasswordAreAccepted() {
        assertNull(AuthInteractionRules.inputError(" Alice@example.test ", "  exact password  "))
    }
    @Test fun boundsAreEnforced() {
        assertNotNull(AuthInteractionRules.inputError("x".repeat(CloudAuthRules.MAX_USERNAME_LENGTH + 1), "password"))
        assertNotNull(AuthInteractionRules.inputError("alice@example.test", "x".repeat(CloudAuthRules.MAX_PASSWORD_LENGTH + 1)))
    }
    @Test fun normalizationDoesNotChangePassword() {
        assertEquals("alice@example.test", CloudAuthRules.normalizeUsername(" ALICE@example.test "))
        assertNull(AuthInteractionRules.inputError("alice@example.test", " a password with spaces "))
    }
}
