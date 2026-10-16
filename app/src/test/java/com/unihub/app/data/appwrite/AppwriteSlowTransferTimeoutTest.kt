package com.unihub.app.data.appwrite

import com.unihub.app.data.cloud.CloudStorageConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Uses real loopback TCP/HTTP sockets, not a fake HttpURLConnection: a response held for longer
 * than the historical 30-second timeout must fail with that old setting but succeed for both
 * file-transfer paths with the current size-derived timeout.
 */
class AppwriteSlowTransferTimeoutTest {
    @Test
    fun uploadAndDownloadSurviveAResponseStallLongerThanThirtySeconds() = runBlocking {
        val delayMs = 32_000L
        val payload = ByteArray(AppwriteFileProtocol.CHUNK_BYTES) { (it % 251).toByte() }
        val uploadKey = "files/slow-upload.bin"
        val downloadKey = "files/slow-download.bin"
        val uploadId = AppwriteFileProtocol.fileId(uploadKey)
        val downloadId = AppwriteFileProtocol.fileId(downloadKey)
        val uploadName = AppwriteFileProtocol.uploadName(uploadKey)
        val downloadName = AppwriteFileProtocol.uploadName(downloadKey)
        val uploadBytesSeen = AtomicInteger(0)
        val serverError = AtomicReference<Throwable?>(null)
        val filesPath = "/v1/storage/buckets/${TARGET.bucketId}/files"
        val server = LoopbackHttpServer { request ->
            when {
                request.path == "/baseline" -> {
                    Thread.sleep(delayMs)
                    HttpResponse(200, "ok".toByteArray())
                }

                request.path == filesPath && request.method == "POST" -> {
                    uploadBytesSeen.set(request.body.size)
                    Thread.sleep(delayMs)
                    HttpResponse(201, metadata(uploadId, uploadName, payload.size))
                }

                request.path == "$filesPath/$uploadId" -> HttpResponse(
                    404,
                    """{"type":"storage_file_not_found"}""".toByteArray()
                )

                request.path == "$filesPath/$downloadId" -> HttpResponse(
                    200,
                    metadata(downloadId, downloadName, payload.size)
                )

                request.path == "$filesPath/$downloadId/download" -> {
                    Thread.sleep(delayMs)
                    HttpResponse(200, payload, "application/octet-stream")
                }

                else -> HttpResponse(404, """{"type":"not_found"}""".toByteArray())
            }
        }
        server.onFailure = { requestPath, error ->
            // The historical client closes its socket at 30 seconds; the delayed baseline
            // handler then cannot write its response. That disconnect is the expected result.
            if (requestPath != "/baseline") serverError.compareAndSet(null, error)
        }
        server.start()

        val connections = CopyOnWriteArrayList<HttpURLConnection>()
        val baseUrl = "http://127.0.0.1:${server.port}"
        val transport = AppwriteStorageTransport(
            TARGET,
            TestAuthorizer(),
            openConnection = { original ->
                (URL("$baseUrl${original.file}").openConnection() as HttpURLConnection)
                    .also(connections::add)
            }
        )
        val uploadSource = File.createTempFile("appwrite-slow-upload", ".bin").apply { writeBytes(payload) }
        val downloadTarget = File.createTempFile("appwrite-slow-download", ".bin")

        try {
            // Exact old behavior in the pre-fix Git source: readTimeout = 30_000.
            val oldConnection = URL("$baseUrl/baseline").openConnection() as HttpURLConnection
            oldConnection.readTimeout = 30_000
            val oldStart = System.nanoTime()
            try {
                oldConnection.inputStream.use { it.readBytes() }
                throw AssertionError("The historical 30-second timeout unexpectedly accepted a 32-second stall")
            } catch (_: SocketTimeoutException) {
                val oldElapsedMs = elapsedMs(oldStart)
                assertTrue("Old timeout did not fire near 30 seconds: ${oldElapsedMs}ms", oldElapsedMs in 29_000..31_500)

                val uploadStart = System.nanoTime()
                val uploadTask = async(Dispatchers.IO) {
                    transport.uploadFile(TARGET, uploadKey, uploadSource).getOrThrow()
                }
                val downloadStart = System.nanoTime()
                val downloadTask = async(Dispatchers.IO) {
                    transport.downloadFile(TARGET, downloadKey, downloadTarget).getOrThrow()
                }
                val uploadResult = uploadTask.await()
                val downloadResult = downloadTask.await()
                val uploadElapsedMs = elapsedMs(uploadStart)
                val downloadElapsedMs = elapsedMs(downloadStart)
                val actualTransferTimeoutMs = connections.maxOf { it.readTimeout }.toLong()

                assertEquals(payload.size.toLong(), uploadResult.size)
                assertTrue(downloadResult)
                assertTrue("Upload request body was not sent", uploadBytesSeen.get() > payload.size)
                assertTrue("Downloaded file differs from the server response", downloadTarget.readBytes().contentEquals(payload))
                assertTrue("The request did not use an extended transfer timeout", actualTransferTimeoutMs > delayMs)
                assertTrue("Upload returned before the server delay", uploadElapsedMs >= delayMs)
                assertTrue("Download returned before the server delay", downloadElapsedMs >= delayMs)
                assertTrue("Local server reported an error: ${serverError.get()}", serverError.get() == null)

                println(
                    "SLOW_TRANSFER_METRICS historical_read_timeout_ms=30000 " +
                        "historical_failure_ms=$oldElapsedMs transfer_read_timeout_ms=$actualTransferTimeoutMs " +
                        "upload_5MiB_success_ms=$uploadElapsedMs download_5MiB_success_ms=$downloadElapsedMs"
                )
            } finally {
                oldConnection.disconnect()
            }
        } finally {
            server.close()
            uploadSource.delete()
            downloadTarget.delete()
        }
    }

    private class TestAuthorizer : StorageAuthorizer {
        private val grant = StorageGrant("storage.jwt.signature", "slow-transfer-test", 1, Long.MAX_VALUE)
        override suspend fun grant(): StorageGrant = grant
        override fun requireCurrent(grant: StorageGrant) = Unit
        override fun clear() = Unit
    }

    private fun metadata(id: String, name: String, size: Int): ByteArray = JSONObject()
        .put("\$id", id)
        .put("name", name)
        .put("sizeOriginal", size)
        .put("signature", "slow-transfer-test")
        .put("\$createdAt", "2026-10-16T00:00:00Z")
        .put("\$updatedAt", "2026-10-16T00:00:00Z")
        .put("chunksTotal", 1)
        .put("chunksUploaded", 1)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private class LoopbackHttpServer(
        private val handler: (HttpRequest) -> HttpResponse
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newFixedThreadPool(6)
        @Volatile private var closed = false
        @Volatile var onFailure: (String, Throwable) -> Unit = { _, _ -> }
        val port: Int get() = socket.localPort

        fun start() {
            executor.execute {
                while (!closed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    executor.execute { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) {
            var requestPath = ""
            try {
                client.use { connection ->
                    connection.soTimeout = 60_000
                    val input = BufferedInputStream(connection.getInputStream())
                    val line = input.readHttpLine() ?: return
                    val parts = line.split(' ')
                    if (parts.size < 2) throw IOException("Malformed HTTP request line")
                    val method = parts[0]
                    requestPath = parts[1].substringBefore('?')
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val header = input.readHttpLine() ?: break
                        if (header.isEmpty()) break
                        val separator = header.indexOf(':')
                        if (separator > 0) {
                            headers[header.substring(0, separator).trim().lowercase()] =
                                header.substring(separator + 1).trim()
                        }
                    }
                    val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(bodyLength)
                    var offset = 0
                    while (offset < bodyLength) {
                        val count = input.read(body, offset, bodyLength - offset)
                        if (count < 0) throw IOException("Request body ended before Content-Length")
                        offset += count
                    }
                    val response = handler(HttpRequest(method, requestPath, body))
                    val output = connection.getOutputStream()
                    val reason = when (response.status) {
                        200 -> "OK"
                        201 -> "Created"
                        404 -> "Not Found"
                        else -> "Error"
                    }
                    output.write(
                        ("HTTP/1.1 ${response.status} $reason\r\n" +
                            "Content-Type: ${response.contentType}\r\n" +
                            "Content-Length: ${response.body.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
                    )
                    output.write(response.body)
                    output.flush()
                }
            } catch (error: Throwable) {
                onFailure(requestPath, error)
                runCatching { client.close() }
            }
        }

        private fun BufferedInputStream.readHttpLine(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = read()
                if (next < 0) return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
                if (next == '\n'.code) {
                    val line = bytes.toByteArray()
                    val length = if (line.lastOrNull() == '\r'.code.toByte()) line.size - 1 else line.size
                    return String(line, 0, length, StandardCharsets.US_ASCII)
                }
                bytes.write(next)
            }
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
            executor.shutdownNow()
        }
    }

    private data class HttpRequest(val method: String, val path: String, val body: ByteArray)
    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/json"
    )

    companion object {
        private val TARGET = CloudStorageConnection()
    }
}
