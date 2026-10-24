package com.unihub.app.data.supabase

import com.unihub.app.data.cloud.CloudSessionExpiredException
import com.unihub.app.data.cloud.StorageAuthorizer
import com.unihub.app.data.cloud.StorageGrant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * تفويض الوصول إلى ملفات السحابة.
 *
 * في Appwrite كان هنا **جسر كامل**: هوية «ظل» لكل مستخدم في مشروع الملفات
 * ورمز تخزين مدته 300 ثانية. في Supabase لا حاجة لأيٍّ منهما: **رمز المستخدم
 * نفسه** هو التفويض، وسياسات RLS على `storage.objects` هي ما يحدّد ما يراه
 * المستخدم وما لا يراه. ⇒ حُذفت الهوية الظل ورمز التخزين القصير العمر.
 *
 * بقي الحدّ نفسه لسببين:
 *  1) تتيح لكل اختبار بروتوكول استخدام تفويض وهمي بلا شبكة.
 *  2) التمييز بين «انتهت الجلسة» و«تعذّر الاتصال» في رسالة المستخدم.
 */
@Singleton
class SupabaseStorageAuthorization @Inject constructor(
    private val auth: SupabaseAuthRepository
) : StorageAuthorizer {

    private val mutex = Mutex()
    @Volatile private var cached: StorageGrant? = null

    override fun clear() {
        cached = null
    }

    override fun requireCurrent(grant: StorageGrant) {
        if (!auth.isCurrent(grant.sessionVersion, grant.authUserId)) {
            throw CloudSessionExpiredException("انتهت جلسة المستخدم أثناء نقل الملفات")
        }
    }

    override suspend fun grant(): StorageGrant = mutex.withLock {
        val version = auth.sessionVersion
        val userId = auth.authenticatedUserId
        if (userId.isBlank()) throw IOException("سجّل الدخول قبل الوصول إلى ملفات السحابة")
        val token = auth.validAccessToken()
        if (token.isBlank()) throw IOException("سجّل الدخول قبل الوصول إلى ملفات السحابة")
        val existing = cached
        if (existing != null && existing.sessionVersion == version &&
            existing.authUserId == userId && existing.jwt == token) {
            requireCurrent(existing)
            return@withLock existing
        }
        val grant = StorageGrant(token, userId, version, Long.MAX_VALUE)
        requireCurrent(grant)
        cached = grant
        grant
    }
}
