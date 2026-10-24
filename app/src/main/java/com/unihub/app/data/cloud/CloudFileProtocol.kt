package com.unihub.app.data.cloud

import java.security.MessageDigest
import java.util.Base64

/**
 * قواعد بروتوكول الملفات — محايدة المزوّد.
 *
 * كان هذا الملف مربوطًا بـ Appwrite (`AppwriteFileProtocol`) رغم أن منطقه
 * محايد تمامًا: اشتقاق هوية الملف، واسم الرفع، ومعرّف جيل البيان، وقواعد
 * الأمان للمفاتيح. نُقل إلى طبقة السحابة لأن Appwrite لم يعد مزوّد التطبيق،
 * ولأن SharedPreferences/CloudCatalogStore ما زالت تعتمد على **صيغة** هذا
 * المعرّف — فتغيير الصيغة يكسر روابط الملفات المحلية.
 *
 * الاستبدال لم يغيّر أي ثابت أو دالة: القيمة المولّدة متطابقة بايتًا ببايت،
 * وبذلك تبقى روابط المكتبة المحلية صالحة بعد الترحيل.
 */
object CloudFileProtocol {
    const val CHUNK_BYTES = 5 * 1024 * 1024
    // البيان يُنشر في عملية واحدة ذرّية، لا في رفع متعدد الأجزاء.
    const val MAX_MANIFEST_BYTES = CHUNK_BYTES - 1
    const val PAGE_SIZE = 100
    private const val NAME_PREFIX = "uh1_"
    private const val EXTERNAL_PREFIX = "external:"
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,35}")
    private val revisionPattern = Regex("m[0-9]{16}_[0-9a-f]{17}")

    fun validId(value: String): Boolean = idPattern.matches(value)

    fun isSafeObjectKey(key: String): Boolean = key.isNotBlank() &&
        key.toByteArray(Charsets.UTF_8).size <= 180 && !key.startsWith('/') &&
        key.none { it.isISOControl() || it == '\\' } &&
        key.split('/').none { it == "." || it == ".." || it.isEmpty() }

    fun fileId(key: String): String {
        require(isSafeObjectKey(key)) { "مفتاح الملف غير صالح" }
        if (key.startsWith(EXTERNAL_PREFIX)) {
            return key.removePrefix(EXTERNAL_PREFIX).also { require(validId(it)) }
        }
        return "f" + sha256(key).take(35)
    }

    fun uploadName(key: String): String {
        require(isSafeObjectKey(key) && !key.startsWith(EXTERNAL_PREFIX))
        return NAME_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(key.toByteArray(Charsets.UTF_8)) + ".bin"
    }

    fun logicalKey(id: String, name: String): String {
        require(validId(id))
        if (name.startsWith(NAME_PREFIX) && name.endsWith(".bin")) {
            val decoded = runCatching {
                String(Base64.getUrlDecoder().decode(name.removePrefix(NAME_PREFIX).removeSuffix(".bin")), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null && isSafeObjectKey(decoded) && !decoded.startsWith(EXTERNAL_PREFIX) && fileId(decoded) == id) {
                return decoded
            }
        }
        return EXTERNAL_PREFIX + id
    }

    fun isManifest(id: String, name: String): Boolean = revisionPattern.matches(id) &&
        name == CloudStorageConfig.REMOTE_MANIFEST_OBJECT_KEY

    fun manifestGeneration(id: String): Long {
        require(revisionPattern.matches(id)) { "معرّف إصدار الفهرس غير صالح" }
        return id.substring(1, 17).toLong()
    }

    /**
     * كل الكتّاب الذين يتقدّمون من القاعدة نفسها يختارون المعرّف التالي نفسه.
     * قيد التفرّد في قاعدة البيانات يجعل الخاسر يفشل بدل أن يكتب فوق الآخر،
     * فيتحوّل ذلك إلى «تعارض» يُعرض للمستخدم بدل فقدان بيانات.
     */
    fun nextManifestId(previous: String?): String {
        val generation = previous?.let { manifestGeneration(it) + 1 } ?: 0L
        require(generation in 0..9_999_999_999_999_999L)
        return "m${generation.toString().padStart(16, '0')}_${sha256(previous ?: "unihub-manifest-root-v1").take(17)}"
    }

    /**
     * أحدث جيل من إصدارات البيان.
     * وجود إصدارين بالجيل نفسه يعني كتابة متزامنة ⇒ تعارض، لا تخمين.
     */
    fun latestManifestId(ids: List<String>): String? {
        val revisions = ids.filter { revisionPattern.matches(it) }
        if (revisions.isEmpty()) return null
        val latest = revisions.maxByOrNull { manifestGeneration(it) } ?: return null
        if (revisions.any { it != latest && manifestGeneration(it) == manifestGeneration(latest) }) {
            throw CloudConflictException()
        }
        return latest
    }

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun chunkLength(size: Long, offset: Long): Int {
        require(size >= 0 && offset in 0..size)
        return minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
    }
}
