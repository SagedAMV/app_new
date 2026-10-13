package com.unihub.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudFailureMessagesTest {
    @Test
    fun unauthorizedMeansExpiredSessionButForbiddenMeansMissingPermission() {
        assertEquals(CloudFailureMessages.SESSION, CloudFailureMessages.messageFor(401))
        assertEquals(CloudFailureMessages.PERMISSION_DENIED, CloudFailureMessages.messageFor(403))
    }

    @Test
    fun statusMessagesNeverExposeRawHttpDetails() {
        listOf(401, 403, 404, 409, 413, 429, 503).forEach { status ->
            val message = CloudFailureMessages.messageFor(status)
            assertEquals("message for $status should be user-facing", true, CloudFailureMessages.isUserFacing(message))
            assertEquals("message for $status should not expose status numbers", false, message.contains(status.toString()))
        }
    }
}
