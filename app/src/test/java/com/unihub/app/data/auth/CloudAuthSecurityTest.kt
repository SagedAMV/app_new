package com.unihub.app.data.auth

import org.junit.Assert.assertEquals
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
        val weak = CloudAuthRules.evaluateLogin(null, "saged", "123456", device, isOnline = true, allowBootstrap = true)
        assertTrue(weak is AuthLoginOutcome.Rejected)

        val strongPassword = "correct horse battery staple 2026"
        val noConsent = CloudAuthRules.evaluateLogin(null, "saged", strongPassword, device, isOnline = true)
        assertTrue(noConsent is AuthLoginOutcome.Rejected)
        val accepted = CloudAuthRules.evaluateLogin(null, "saged", strongPassword, device, isOnline = true, allowBootstrap = true)
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

    @Test
    fun newPasswordsPreserveLeadingAndTrailingSpaces() {
        val password = "  a long exact password 2026  "
        val account = CloudUserAccount("alice", "Alice", CloudAuthRules.hashPassword("alice", password))
        assertTrue(CloudAuthRules.verifyPassword(password, account))
        assertFalse(CloudAuthRules.verifyPassword(password.trim(), account))
        assertTrue(account.passwordHash.startsWith("pbkdf2-sha256-v2$600000$"))
    }

    @Test
    fun absentPermissionsAndForgedSecondaryAdminFailClosed() {
        val json = """{"users":[{"username":"saged","isActive":true},
          {"username":"alice","isAdmin":true,"isActive":true},
          {"username":"bob"}]}"""
        val registry = CloudAuthRules.fromJson(json)
        val alice = registry.users.first { it.username == "alice" }
        assertFalse(alice.isAdmin)
        assertEquals(UserPermissions.NONE, alice.permissions)
        assertFalse(CloudAuthRules.checkPermission(alice, AuthPermission.UPLOAD).isSuccess)
        assertFalse(registry.users.first { it.username == "bob" }.isActive)
        assertEquals(UserPermissions.NONE, UserPermissions())
    }

    @Test
    fun diskProfileContainsOnlyCurrentUserAndNoPasswordVerifiers() {
        val registry = CloudAuthRegistry(users = listOf(
            CloudUserAccount("saged", "Owner", "owner-verifier", isAdmin = true),
            CloudUserAccount("alice", "Alice", "alice-verifier"),
            CloudUserAccount("bob", "Bob", "bob-verifier")
        ))
        val profile = CloudAuthRules.redactedRegistry(registry, "alice")
        assertEquals(listOf("alice"), profile.users.map { it.username })
        assertTrue(profile.users.all { it.passwordHash.isEmpty() })
        val json = CloudAuthRules.toJson(profile)
        assertFalse(json.contains("owner-verifier"))
        assertFalse(json.contains("alice-verifier"))
        assertFalse(json.contains("bob-verifier"))
    }

    @Test
    fun cachedSessionHasBoundedLifetimeAndRejectsClockRollback() {
        val now = 10_000L
        assertTrue(CloudAuthRules.isCachedSessionFresh(now, now))
        assertFalse(CloudAuthRules.isCachedSessionFresh(0, now))
        assertFalse(CloudAuthRules.isCachedSessionFresh(now + 1, now))
        assertFalse(CloudAuthRules.isCachedSessionFresh(now, now + CloudAuthRules.MAX_OFFLINE_SESSION_AGE_MS + 1))
    }


    @Test
    fun malformedBooleanAndFutureSchemaDoNotGrantAuthority() {
        val registry = CloudAuthRules.fromJson("""{"users":[{"username":"alice","isActive":"true",
          "permissions":{"canUpload":"true","canDownload":1,"canModify":true}}]}""")
        val alice = registry.users.single()
        assertFalse(alice.isActive)
        assertFalse(alice.permissions.canUpload)
        assertFalse(alice.permissions.canDownload)
        assertTrue(alice.permissions.canModify)
        assertTrue(CloudAuthRules.fromJson("""{"schemaVersion":99,"users":[{"username":"saged"}]}""").users.isEmpty())
    }

    @Test
    fun legacyPbkdf2IsAcceptedThenUpgradedWithoutChangingItsOldTrimRule() {
        val password = "old compatible password 2026"
        val salt = ByteArray(16) { it.toByte() }
        val spec = javax.crypto.spec.PBEKeySpec("saged\u0000$password".toCharArray(), salt, 210_000, 256)
        val hash = try { javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val b64 = java.util.Base64.getEncoder().withoutPadding()
        val encoding = listOf("pbkdf2-sha256", "210000", b64.encodeToString(salt), b64.encodeToString(hash)).joinToString("$")
        val device = BoundDeviceInfo("legacy-test-device", "Test device", boundAt = 1)
        val account = CloudUserAccount("saged", "Owner", encoding, isAdmin = true, boundDevice = device)
        assertTrue(CloudAuthRules.verifyPassword("  $password  ", account))
        val outcome = CloudAuthRules.evaluateLogin(CloudAuthRegistry(users = listOf(account)), "saged", "  $password  ", device, true)
        assertTrue(outcome is AuthLoginOutcome.Authenticated)
        outcome as AuthLoginOutcome.Authenticated
        assertTrue(CloudAuthRules.isCurrentPasswordHash(outcome.user.passwordHash))
        assertTrue(CloudAuthRules.verifyPassword(password, outcome.user))
        assertFalse(CloudAuthRules.verifyPassword("  $password  ", outcome.user))
    }

    @Test
    fun hostileEncodedWorkFactorIsRejectedBeforeDerivation() {
        val account = CloudUserAccount("alice", "Alice", "pbkdf2-sha256-v2\$999999999\$AAAA\$AAAA")
        assertFalse(CloudAuthRules.verifyPassword("password", account))
    }

}
