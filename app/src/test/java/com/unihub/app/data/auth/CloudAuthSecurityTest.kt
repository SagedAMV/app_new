package com.unihub.app.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class CloudAuthSecurityTest {

    @Test
    fun passwordHashIsSaltedOneWayAndVerifiesOnlyTheCorrectPassword() {
        val password = "a long unique password 2026"
        val first = CloudAuthRules.createDefaultAdminAccount(password)
        val second = CloudAuthRules.createDefaultAdminAccount(password)

        assertTrue(CloudAuthRules.isCurrentPasswordHash(first.passwordHash))
        assertTrue(CloudAuthRules.verifyPassword(password, first))
        assertFalse(CloudAuthRules.verifyPassword("not the password", first))
        assertFalse(first.passwordHash.contains(password))
        assertFalse(first.passwordHash == second.passwordHash)
    }

    @Test
    fun missingAdministratorIsNotSilentlyRecreated() {
        val registry = CloudAuthRegistry(
            users = listOf(
                CloudUserAccount(
                    username = "student",
                    displayName = "Student",
                    passwordHash = CloudAuthRules.hashPassword("student", "a good student password"),
                    isAdmin = false
                )
            )
        )

        val sanitized = CloudAuthRules.ensureAdminInvariants(registry)
        assertTrue(sanitized.users.none { CloudAuthRules.normalizeUsername(it.username) == CloudAuthRules.ADMIN_USERNAME })
        assertTrue(sanitized.users.any { it.username == "student" })
    }

    @Test
    fun firstRunAdminBootstrapRequiresStrongPasswordAndAnAbsentRegistry() {
        val device = BoundDeviceInfo(
            fingerprint = "device-fingerprint-01",
            deviceName = "Test device",
            boundAt = 1L
        )
        val weak = CloudAuthRules.evaluateLogin(null, "saged", "123456", device, isOnline = true)
        assertTrue(weak is AuthLoginOutcome.Rejected)

        val strongPassword = "correct horse battery staple 2026"
        val accepted = CloudAuthRules.evaluateLogin(null, "saged", strongPassword, device, isOnline = true)
        assertTrue(accepted is AuthLoginOutcome.Authenticated)
        accepted as AuthLoginOutcome.Authenticated
        assertTrue(accepted.user.isAdmin)
        assertTrue(accepted.registryChanged)
        assertTrue(CloudAuthRules.verifyPassword(strongPassword, accepted.user))

        val existingEmptyRegistry = CloudAuthRegistry(users = emptyList())
        val mustNotReset = CloudAuthRules.evaluateLogin(
            existingEmptyRegistry, "saged", strongPassword, device, isOnline = true
        )
        assertTrue(mustNotReset is AuthLoginOutcome.Rejected)
    }

    @Test
    fun successfulLegacyPasswordCheckUpgradesHashToPbkdf2() {
        val password = "old legacy password 123"
        val username = "saged"
        val legacyPepper = "UniHub::CloudAuth::2026::SagedAdminVaultKey::v1"
        val legacyDigest = MessageDigest.getInstance("SHA-256")
            .digest("$legacyPepper::${username}::$password".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val device = BoundDeviceInfo("device-fingerprint-02", "Test device", boundAt = 1L)
        val legacyAdmin = CloudUserAccount(
            username = username,
            displayName = username,
            passwordHash = legacyDigest,
            isAdmin = true,
            boundDevice = device
        )
        val outcome = CloudAuthRules.evaluateLogin(
            CloudAuthRegistry(users = listOf(legacyAdmin)),
            username,
            password,
            device,
            isOnline = true
        )

        assertTrue(outcome is AuthLoginOutcome.Authenticated)
        outcome as AuthLoginOutcome.Authenticated
        assertTrue(CloudAuthRules.isCurrentPasswordHash(outcome.user.passwordHash))
        assertTrue(CloudAuthRules.verifyPassword(password, outcome.user))
        assertTrue(outcome.registryChanged)
    }
}
