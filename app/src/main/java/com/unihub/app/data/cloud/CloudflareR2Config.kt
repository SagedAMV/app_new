package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FileKind

/**
 * الإعدادات المركزية لربط التطبيق بخادم Cloudflare R2.
 *
 * القيم العامة الافتراضية تساعد على ملء الحقول فقط؛ مفاتيح الوصول لا تُضمّن في التطبيق.
 * يجب ضبط أسرار R2 على الجهاز قبل استخدام السحابة.
 */
object CloudflareR2Config {

    /**
     * معرّف حساب Cloudflare (Account ID) — يُستخدم لبناء رابط S3 التلقائي:
     * https://<ACCOUNT_ID>.r2.cloudflarestorage.com
     */
    const val DEFAULT_ACCOUNT_ID: String = "78c962a13843039b1bc4e0ef91bb4235"

    /**
     * رابط نقطة النهاية (Endpoint URL)
     */
    const val DEFAULT_ENDPOINT_URL: String = "https://78c962a13843039b1bc4e0ef91bb4235.r2.cloudflarestorage.com"

    /** اسم الحاوية (Bucket Name) في Cloudflare R2 */
    const val DEFAULT_BUCKET_NAME: String = "class-new"

    /** مفاتيح الوصول لا تُضمّن في المصدر أو APK؛ يضيفها المشرف محليًا. */
    const val DEFAULT_ACCESS_KEY_ID: String = ""
    const val DEFAULT_SECRET_ACCESS_KEY: String = ""

    /** المنطقة الافتراضية المعتمدة في Cloudflare R2 لتوقيع AWS SigV4 */
    const val DEFAULT_REGION: String = "auto"

    /** اسم ملف أرشيف النسخة الكاملة على الخادم */
    const val REMOTE_BACKUP_OBJECT_KEY: String = "unihub_cloud_backup.zip"

    /** اسم ملف الفهرس والبيانات النصية الخفيفة (المجلدات/المهام/الملاحظات/الامتحانات/الجدول + قائمة الملفات وأحجامها) */
    const val REMOTE_MANIFEST_OBJECT_KEY: String = "unihub_manifest.json"

    /** اسم ملف البيانات الوصفية الخفيف لفحص أحدث نسخة على الخادم بسرعة */
    const val REMOTE_META_OBJECT_KEY: String = "unihub_cloud_meta.json"

    /** اسم ملف سجل المصادقة والمستخدمين والأجهزة والصلاحيات المحمي على السحابة */
    const val REMOTE_AUTH_OBJECT_KEY: String = "unihub_auth_registry.json"

    /** بادئة مسار الملفات الفيزيائية المستقلة على الخادم للسحب الانتقائي */
    const val REMOTE_FILES_PREFIX: String = "files/"

    fun remoteFileObjectKey(id: Long, extension: String): String {
        val cleanExt = extension.trim().trim('.').filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        return "${REMOTE_FILES_PREFIX}file_${id}.$cleanExt"
    }
}

/**
 * يمثّل ملفاً موجوداً على خادم Cloudflare R2 وغير محمّل بعد على الهاتف،
 * ليظهر في الإشعار وفي قائمة الاختيار الانتقائي مع اسمه وحجمه.
 */
data class RemoteCloudFile(
    /** المفتاح الفعلي للكائن داخل حاوية Cloudflare R2 */
    val remoteKey: String,
    /** معرّف السجل في فهرس التطبيق (أو 0 إن كان ملفاً مرفوعاً مباشرة للحاوية) */
    val id: Long,
    /** اسم الملف بدون امتداد */
    val name: String,
    /** امتداد الملف (pdf, jpg, m4a, ...) */
    val extension: String,
    /** الحجم الفعلي بالبايت */
    val size: Long,
    /** نوع MIME */
    val mimeType: String = "application/octet-stream",
    /** تصنيف الملف في التطبيق */
    val kind: FileKind = FileKind.OTHER,
    /** معرّف المجلد الأب إن وُجد */
    val folderId: Long? = null,
    /** اسم المجلد الأب للعرض للمستخدم في نافذة الاختيار */
    val folderName: String? = null,
    /** وقت الإنشاء */
    val createdAt: Long = System.currentTimeMillis(),
    /** ETag معرف نسخة الكائن؛ ليس بالضرورة MD5، خصوصاً عند الرفع متعدد الأجزاء. */
    val etag: String = "",
    val sha256: String = "",
    val lastModifiedAt: Long = 0L,
    val cloudFolderKey: String? = null
) {
    val versionToken: String
        get() = "${etag.ifBlank { sha256.ifBlank { lastModifiedAt.toString() } }}:$size"

    val notificationToken: String
        get() = "$remoteKey\u0000$versionToken"
    val fullDisplayName: String
        get() = if (extension.isNotBlank() && !name.endsWith(".$extension", ignoreCase = true)) {
            "$name.$extension"
        } else {
            name
        }
}

/**
 * ملخص كائن خام مستخرج من فحص حاوية Cloudflare R2 (ListObjectsV2).
 */
data class R2ObjectSummary(
    val key: String,
    val size: Long,
    val etag: String,
    val lastModifiedAt: Long = 0L
)

/**
 * لقطة مكتملة لبيانات الاتصال بخادم Cloudflare R2.
 */
data class R2Credentials(
    val accountId: String = CloudflareR2Config.DEFAULT_ACCOUNT_ID,
    val endpointUrl: String = CloudflareR2Config.DEFAULT_ENDPOINT_URL,
    val bucketName: String = CloudflareR2Config.DEFAULT_BUCKET_NAME,
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val region: String = CloudflareR2Config.DEFAULT_REGION
) {
    val resolvedEndpoint: String
        get() {
            val cleanEndpoint = endpointUrl.trim().trimEnd('/')
            if (cleanEndpoint.isNotBlank()) {
                return if (cleanEndpoint.startsWith("http://") || cleanEndpoint.startsWith("https://")) {
                    cleanEndpoint
                } else {
                    "https://$cleanEndpoint"
                }
            }
            val cleanAccount = accountId.trim()
            return if (cleanAccount.isNotBlank()) {
                "https://$cleanAccount.r2.cloudflarestorage.com"
            } else {
                ""
            }
        }

    /**
     * R2 credentials are sent only to the canonical HTTPS endpoint for the configured
     * Cloudflare account. Arbitrary endpoints are rejected to prevent credential exfiltration.
     */
    val isConfigured: Boolean
        get() {
            val cleanAccount = accountId.trim().lowercase(java.util.Locale.ROOT)
            val cleanBucket = bucketName.trim()
            val cleanAccess = accessKeyId.trim()
            val cleanSecret = secretAccessKey.trim()
            if (!cleanAccount.matches(Regex("[0-9a-f]{32}")) ||
                !cleanBucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) ||
                ".." in cleanBucket ||
                cleanAccess.isEmpty() || cleanAccess.length > 256 ||
                cleanSecret.isEmpty() || cleanSecret.length > 512 ||
                cleanAccess.any(Char::isISOControl) || cleanSecret.any(Char::isISOControl)) {
                return false
            }
            val uri = runCatching { java.net.URI(resolvedEndpoint) }.getOrNull() ?: return false
            val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
            val expectedHost = "$cleanAccount.r2.cloudflarestorage.com"
            return uri.scheme.equals("https", ignoreCase = true) &&
                host == expectedHost && (uri.port == -1 || uri.port == 443) &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        }

    val useS3Protocol: Boolean
        get() = isConfigured
}
