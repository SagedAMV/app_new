package com.unihub.app.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** لا يعمل افتراضياً ولا يكتب بيانات تطبيق؛ يلزم UNIHUB_RUN_R2_TEST=1 صراحةً. */
class R2ClientIntegrationTest {
    @Test fun actualKotlinClientUploadsListsStreamsAndRejectsChangedVersions() = runBlocking {
        assumeTrue("اختبار R2 الحي معطّل افتراضياً", System.getenv("UNIHUB_RUN_R2_TEST") == "1")
        val client = CloudflareR2Client()
        val credentials = R2Credentials()
        val prefix = "unihub-tests/${UUID.randomUUID()}/"
        val key = prefix + "محاضرة 1 + &?#.pdf"
        val zeroKey = prefix + "empty.txt"
        val source = File.createTempFile("r2_source_", ".bin")
        val target = File.createTempFile("r2_target_", ".bin")
        val empty = File.createTempFile("r2_empty_", ".txt")
        try {
            source.outputStream().use { out ->
                val chunk = ByteArray(16 * 1024) { (it % 251).toByte() }
                repeat(128) { out.write(chunk) }
            }
            val uploaded = client.uploadFile(credentials, key, source, "application/pdf").getOrThrow()
            assertTrue(uploaded.etag.isNotBlank())
            val remote = client.listBucketObjects(credentials).getOrThrow().single { it.key == key }
            assertEquals(source.length(), remote.size)
            assertEquals(uploaded.etag, remote.etag)
            var progressed = 0L
            var streamedHash = ""
            assertTrue(client.downloadFile(credentials, key, target, uploaded.etag, source.length(), onDigest = { streamedHash = it }) { bytes, _ -> progressed = bytes }.getOrThrow())
            assertEquals(source.length(), progressed)
            assertEquals(client.sha256Hex(source), streamedHash)
            assertEquals(client.sha256Hex(source), client.sha256Hex(target))
            val conflict = client.downloadFile(credentials, key, target, "wrong-etag", source.length())
            assertTrue(conflict.exceptionOrNull() is CloudConflictException)
            assertFalse(target.exists())
            val wrongSize = client.downloadFile(credentials, key, target, uploaded.etag, source.length() + 1)
            assertTrue(wrongSize.isFailure)
            assertFalse(target.exists())
            var cancellationThrown = false
            try {
                client.downloadFile(credentials, key, target, uploaded.etag, source.length()) { _, _ ->
                    throw CancellationException("إلغاء تجريبي")
                }
            } catch (_: CancellationException) { cancellationThrown = true }
            assertTrue("الإلغاء يجب أن يمر كإلغاء لا فشل صامت", cancellationThrown)
            assertFalse("لا يبقى تنزيل جزئي", target.exists())
            val zero = client.uploadFile(credentials, zeroKey, empty, "text/plain").getOrThrow()
            assertTrue(client.downloadFile(credentials, zeroKey, target, zero.etag, 0).getOrThrow())
            assertEquals(0L, target.length())
        } finally {
            client.deleteTestObject(credentials, key).getOrThrow()
            client.deleteTestObject(credentials, zeroKey).getOrThrow()
            source.delete(); target.delete(); empty.delete()
        }
        assertFalse(client.listBucketObjects(credentials).getOrThrow().any { it.key.startsWith(prefix) })
    }

    @Test fun conditionalManifestWritesNeverOverwriteNewerRemoteIndex() = runBlocking {
        assumeTrue("اختبار R2 الحي معطّل افتراضياً", System.getenv("UNIHUB_RUN_R2_TEST") == "1")
        val client = CloudflareR2Client()
        val credentials = R2Credentials()
        val key = "unihub-tests/${UUID.randomUUID()}/index.json"
        try {
            val initial = client.uploadText(credentials, key, "{\"version\":1}", ifNoneMatch = true).getOrThrow()
            assertTrue(client.uploadText(credentials, key, "{\"version\":2}", ifNoneMatch = true).exceptionOrNull() is CloudConflictException)
            assertTrue(client.uploadText(credentials, key, "{\"version\":2}", ifMatch = "wrong-version").exceptionOrNull() is CloudConflictException)
            assertEquals("{\"version\":1}", client.downloadText(credentials, key).getOrThrow())
            val updated = client.uploadText(credentials, key, "{\"version\":2}", ifMatch = initial.etag).getOrThrow()
            assertNotEquals(initial.etag, updated.etag)
            assertEquals("{\"version\":2}", client.downloadText(credentials, key).getOrThrow())
        } finally { client.deleteTestObject(credentials, key).getOrThrow() }
    }
    @Test fun folderAndSubfolderIndexRoundTripsOverRealR2() = runBlocking {
        assumeTrue("اختبار R2 الحي معطّل افتراضياً", System.getenv("UNIHUB_RUN_R2_TEST") == "1")
        val client = CloudflareR2Client(); val creds = R2Credentials()
        val prefix = "unihub-tests/${UUID.randomUUID()}/"
        val key = prefix + "صور/رحلة/صورة.jpg"
        val index = prefix + "folder-index.json"
        val source = File.createTempFile("r2_folder_", ".jpg")
        try {
            source.writeBytes(ByteArray(12_345) { (it % 251).toByte() })
            var sent = 0L
            val uploaded = client.uploadFile(creds, key, source, "image/jpeg") { done, _ -> sent = done }.getOrThrow()
            assertEquals(source.length(), sent)
            val root = RemoteCloudFolder("folder:photos", "صور")
            val child = RemoteCloudFolder("folder:trip", "رحلة", root.key)
            val file = RemoteCloudFile(key, 0, "صورة", "jpg", source.length(), mimeType = "image/jpeg",
                etag = uploaded.etag, cloudFolderKey = child.key)
            val manifest = JSONObject().put("app", "unihub").put("schemaVersion", 3)
                .put("cloudFolders", CloudFolderTree.mergeFolders(null, listOf(root, child)))
                .put("files", JSONArray().put(remoteFileToJson(file)))
            client.uploadText(creds, index, manifest.toString()).getOrThrow()
            val fetched = JSONObject(client.downloadText(creds, index).getOrThrow()!!)
            val fetchedFile = remoteFileFromJson(fetched.getJSONArray("files").getJSONObject(0))!!
            val tree = CloudFolderTree.foldersFromManifest(fetched, listOf(fetchedFile), emptyList())
            assertEquals(listOf(root.key, child.key), CloudFolderTree.ancestors(tree, child.key).map { it.key })
            assertEquals(listOf(key), CloudFolderTree.filesWithin(listOf(fetchedFile), tree, root.key).map { it.remoteKey })
        } finally {
            client.deleteTestObject(creds, key).getOrThrow()
            client.deleteTestObject(creds, index).getOrThrow()
            source.delete()
        }
    }

}
