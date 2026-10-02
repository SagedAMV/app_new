package com.unihub.app.data.cloud

import com.unihub.app.data.local.entity.FileKind

/**
 * الإعدادات المركزية لربط التطبيق بخادم Cloudflare R2.
 *
 * القيم الافتراضية مضبوطة على حاوية المستخدم (class-new) وتعمل تلقائياً.
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

    /** معرّف مفتاح الوصول (R2 Access Key ID) */
    const val DEFAULT_ACCESS_KEY_ID: String = "b453018aa0b0ad6101792f5d48bac2d1"

    /** مفتاح الوصول السري (R2 Secret Access Key) */
    const val DEFAULT_SECRET_ACCESS_KEY: String = "955f7969dbd97f17b63f0a6fceaf5711b27744b91387a72dd446700eb6e985e6"

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
    val accessKeyId: String = CloudflareR2Config.DEFAULT_ACCESS_KEY_ID,
    val secretAccessKey: String = CloudflareR2Config.DEFAULT_SECRET_ACCESS_KEY,
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

    val isConfigured: Boolean
        get() {
            val hasEndpoint = resolvedEndpoint.isNotBlank()
            val hasS3Keys = bucketName.isNotBlank() &&
                accessKeyId.isNotBlank() &&
                secretAccessKey.isNotBlank()
            return hasEndpoint && (hasS3Keys || (endpointUrl.isNotBlank() && accessKeyId.isBlank() && bucketName.isBlank()))
        }

    val useS3Protocol: Boolean
        get() = bucketName.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()
}
