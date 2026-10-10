package com.unihub.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CredentialsSecurityTest {
    private val valid = R2Credentials(
        accountId = "78c962a13843039b1bc4e0ef91bb4235",
        bucketName = "class-new",
        accessKeyId = "test-access-key",
        secretAccessKey = "test-secret-key"
    )

    @Test fun acceptsCanonicalHttpsAccountEndpoint() {
        assertTrue(valid.isConfigured)
        assertTrue(valid.copy(endpointUrl = "https://78c962a13843039b1bc4e0ef91bb4235.r2.cloudflarestorage.com:443").isConfigured)
    }

    @Test fun rejectsArbitraryHostAndNonstandardPort() {
        assertFalse(valid.copy(endpointUrl = "https://attacker.example").isConfigured)
        assertFalse(valid.copy(endpointUrl = "https://78c962a13843039b1bc4e0ef91bb4235.r2.cloudflarestorage.com:444").isConfigured)
    }

    @Test fun rejectsMalformedAccountAndBucketNames() {
        assertFalse(valid.copy(accountId = "not-an-account-id").isConfigured)
        assertFalse(valid.copy(bucketName = "../private").isConfigured)
        assertFalse(valid.copy(bucketName = "ab").isConfigured)
    }

    @Test fun rejectsControlCharactersAndOversizedCredentials() {
        assertFalse(valid.copy(accessKeyId = "key\nHeader: injected").isConfigured)
        assertFalse(valid.copy(secretAccessKey = "x".repeat(513)).isConfigured)
    }
}
