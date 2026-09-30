package com.unihub.app.data.cloud

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CloudClientHashTest {
    @Test fun streamedDiskHashMatchesKnownSha256() {
        val file = File.createTempFile("hash_", ".txt")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CloudflareR2Client().sha256Hex(file))
        } finally { file.delete() }
    }
    @Test fun diskHashingDoesNotIgnoreCancellation() {
        val file = File.createTempFile("hash_cancel_", ".txt")
        try {
            file.writeText("abc")
            var cancelled = false
            try { CloudflareR2Client().sha256Hex(file) { throw CancellationException("stop") } }
            catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
        } finally { file.delete() }
    }
}
