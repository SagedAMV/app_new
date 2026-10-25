package com.unihub.app.data.supabase

/**
 * إحداثيات Supabase العامة فقط.
 *
 * «مفتاح النشر» (publishable) مفتاح **عام بحكم التصميم** — هو البديل الحديث
 * لمفتاح anon، وهو موصول لعميل Android دائمًا، وحمايته ليست في إخفاء المفتاح
 * بل في **سياسات RLS** على الجداول والحاوية. لذلك وجوده هنا ليس تسريبًا.
 *
 * ممنوع منعًا باتًا وضع «المفتاح السري» (secret) في التطبيق أو في
 * ملفات المشروع: هو يتجاوز RLS، ويُستخدم في بيئة الإدارة فقط
 * (ترحيلات القاعدة، إعداد الحاوية، سكربتات الفحص).
 */
object SupabaseConfig {
    const val PROJECT_URL = "https://yjvrkxabhlygdmtoujge.supabase.co"
    /** مرجع المشروع — يُستخدم في ربط ذاكرة الملفات المحلية بالمشروع. */
    const val PROJECT_REF = "yjvrkxabhlygdmtoujge"
    const val PUBLISHABLE_KEY = "sb_publishable_S3VXOoa5TdYWU1cHq-5wEQ_0oy8YO_-"
    const val STORAGE_BUCKET_ID = "unihub-files"

    /**
     * سقف حجم الملف في الحاوية (بايت). مطابق لحدّ الحاوية على الخادم،
     * حتى لا يبدأ التطبيق رفعًا سيرفضه الخادم بعد إرسال الملف.
     */
    const val MAX_UPLOAD_FILE_BYTES: Long = 50_000_000L

    /** مسار المصادقة (GoTrue). */
    const val AUTH_PATH = "/auth/v1"
    const val REST_PATH = "/rest/v1"
    const val STORAGE_PATH = "/storage/v1"

    /** جداول Postgres التي تحل محل كائنات Appwrite. */
    const val TABLE_PROFILES = "profiles"
    const val TABLE_CLOUD_OBJECTS = "cloud_objects"
    const val TABLE_CLOUD_MANIFESTS = "cloud_manifests"

    /** دوال الإدارة (SECURITY DEFINER) — لا تحتاج أي مفتاح إداري من العميل. */
    const val RPC_LIST_USERS = "admin_list_users"
    const val RPC_SET_PERMISSIONS = "admin_set_permissions"
    const val RPC_SET_ACTIVE = "admin_set_active"
    const val RPC_REVOKE_SESSIONS = "admin_revoke_sessions"
    const val RPC_CREATE_USER = "admin_create_user"
    const val RPC_DELETE_USER = "admin_delete_user"
    const val RPC_SET_PASSWORD = "admin_set_password"

}
