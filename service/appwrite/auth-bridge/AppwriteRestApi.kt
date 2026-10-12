package io.openruntimes.kotlin

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Server-only keys. This source directory is NOT an Android/Gradle app source set. */
class AppwriteRestApi(private val authKey: String, private val storageKey: String) : BridgeApi {
    init {
        if (listOf(authKey, storageKey).any { it.isBlank() || it.length > 8192 || it.any(Char::isISOControl) }) {
            throw BridgeFailure(503, "bridge_configuration")
        }
    }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun account(jwt: String): JSONObject = try {
        request(BridgeConfig.AUTH_PROJECT, "GET", "/account", jwt = jwt)
    } catch (error: UpstreamFailure) {
        if (error.status == 401 || error.status == 403) throw BridgeFailure(401, "unauthorized")
        throw error
    }

    override fun auth(method: String, path: String, body: JSONObject?, queries: List<String>): JSONObject =
        request(BridgeConfig.AUTH_PROJECT, method, path, body, queries, key = authKey)

    override fun storage(method: String, path: String, body: JSONObject?): JSONObject =
        request(BridgeConfig.STORAGE_PROJECT, method, path, body, key = storageKey)

    private fun request(project: String, method: String, path: String, body: JSONObject? = null,
        queries: List<String> = emptyList(), key: String? = null, jwt: String? = null): JSONObject {
        check((key != null).xor(jwt != null))
        check(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE"))
        check(Regex("/[A-Za-z0-9/._-]*").matches(path))
        val query = if (queries.isEmpty()) "" else "?" + queries.joinToString("&") {
            "queries%5B%5D=" + URLEncoder.encode(it, "UTF-8")
        }
        val builder = HttpRequest.newBuilder(URI(BridgeConfig.ENDPOINT + path + query))
            .timeout(Duration.ofSeconds(20))
            .header("X-Appwrite-Project", project)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (jwt != null) builder.header("X-Appwrite-JWT", jwt)
        if (key != null) builder.header("X-Appwrite-Key", key)
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it.toString(), Charsets.UTF_8) }
            ?: HttpRequest.BodyPublishers.noBody()
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofInputStream())
        val text = response.body().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1024 * 1024) throw BridgeFailure(502, "upstream_response_limit")
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
        if (response.statusCode() !in 200..299) {
            val type = runCatching { JSONObject(text).optString("type") }.getOrDefault("")
            throw UpstreamFailure(response.statusCode(), type)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
