package com.unihub.app.data.supabase

import com.unihub.app.data.cloud.CloudFileProtocol
import com.unihub.app.data.cloud.CloudStorageConnection
import com.unihub.app.data.cloud.StorageAuthorizer
import com.unihub.app.data.cloud.StorageGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class SupabaseStorageTransportTest {
    private class FakeConnection(
        url: URL,
        private val code: Int,
        private val body: String
    ) : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = code
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream? =
            if (code in 200..299) null else ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getOutputStream(): OutputStream = output
    }

    private object TestAuthorization : StorageAuthorizer {
        override fun clear() = Unit
        override fun requireCurrent(grant: StorageGrant) = Unit
        override suspend fun grant() = StorageGrant("test-user-jwt", "test-user", 1L, Long.MAX_VALUE)
    }

    @Test
    fun uploadTargetsSupabaseStorageObjectEndpoint() = runBlocking {
        val target = CloudStorageConnection(
            endpointUrl = "https://proj.supabase.co",
            projectId = "proj",
            bucketId = "unihub-files"
        )
        val key = "files/proof.txt"
        val requests = mutableListOf<URL>()
        val responses = ArrayDeque(listOf("[]", "{\"Key\":\"unihub-files/proof\"}", ""))
        val codes = ArrayDeque(listOf(200, 200, 201))
        val http = SupabaseHttp("https://proj.supabase.co", "public-key") { url ->
            requests += url
            FakeConnection(url, codes.removeFirst(), responses.removeFirst())
        }
        val source = File.createTempFile("cloud-upload", ".txt").apply { writeText("proof") }

        try {
            val result = SupabaseStorageTransport(target, TestAuthorization, http)
                .uploadFile(target, key, source)

            assertEquals(3, requests.size)
            val storageRequest = requests[1]
            assertEquals("proj.supabase.co", storageRequest.host)
            assertEquals(
                "/storage/v1/object/unihub-files/${CloudFileProtocol.fileId(key)}",
                storageRequest.path
            )
            assertEquals(5L, result.getOrThrow().size)
        } finally {
            source.delete()
        }
    }
}
