package com.unihub.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S3ObjectKeySecurityTest {
    @Test fun acceptsExpectedNestedAppObjectKeys() {
        assertTrue(S3Encoding.isSafeObjectKey("files/0123.pdf"))
        assertTrue(S3Encoding.isSafeObjectKey("unihub_auth_registry.json"))
        assertTrue(S3Encoding.isSafeObjectKey("folder:123"))
    }

    @Test fun rejectsPathTraversalAndControlCharacters() {
        assertFalse(S3Encoding.isSafeObjectKey("../unihub_auth_registry.json"))
        assertFalse(S3Encoding.isSafeObjectKey("files/../../unihub_auth_registry.json"))
        assertFalse(S3Encoding.isSafeObjectKey("/files/0123.pdf"))
        assertFalse(S3Encoding.isSafeObjectKey("files\\private.pdf"))
        assertFalse(S3Encoding.isSafeObjectKey("files/evil\nkey"))
        assertFalse(S3Encoding.isSafeObjectKey("a".repeat(1025)))
    }

    @Test fun deletionRulesNeverAcceptTraversalKeys() {
        assertFalse(CloudDeleteRules.isDeletableObjectKey("../unihub_auth_registry.json"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey("files/../../unihub_auth_registry.json"))
        assertFalse(CloudDeleteRules.isDeletableObjectKey(CloudflareR2Config.REMOTE_AUTH_OBJECT_KEY))
    }
}
