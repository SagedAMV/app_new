package com.unihub.app.data.cloud

import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

object S3Encoding {
    fun component(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    fun path(value: String): String = value.split('/').joinToString("/") { component(it) }
    fun query(params: Map<String, String>): String = params.entries
        .map { component(it.key) to component(it.value) }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { "${it.first}=${it.second}" }
}

data class R2ListPage(val objects: List<R2ObjectSummary>, val nextToken: String?)

/** تحليل XML حقيقي، مع رفض DTD؛ لا Regex ولا قصّ لأسماء الكائنات. */
object R2ListParser {
    fun parse(xml: String): R2ListPage {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw IOException("DTD غير مسموح في قائمة R2")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        fun text(element: org.w3c.dom.Element, tag: String): String =
            element.getElementsByTagNameNS("*", tag).item(0)?.textContent.orEmpty()
        val urlEncoded = text(root, "EncodingType") == "url"
        val nodes = root.getElementsByTagNameNS("*", "Contents")
        val objects = (0 until nodes.length).map { index ->
            val node = nodes.item(index) as org.w3c.dom.Element
            val rawKey = text(node, "Key")
            val key = if (urlEncoded) URLDecoder.decode(rawKey.replace("+", "%2B"), "UTF-8") else rawKey
            val size = text(node, "Size").toLongOrNull() ?: throw IOException("حجم كائن R2 غير صالح")
            if (size < 0) throw IOException("حجم كائن R2 سالب")
            R2ObjectSummary(
                key = key,
                size = size,
                etag = text(node, "ETag").trim('"'),
                lastModifiedAt = runCatching { Instant.parse(text(node, "LastModified")).toEpochMilli() }.getOrDefault(0L)
            )
        }
        val truncated = text(root, "IsTruncated") == "true"
        val next = text(root, "NextContinuationToken").takeIf { it.isNotEmpty() }
        if (truncated && next == null) throw IOException("الخادم أرسل صفحة ناقصة بلا مؤشر متابعة")
        return R2ListPage(objects, if (truncated) next else null)
    }
}
