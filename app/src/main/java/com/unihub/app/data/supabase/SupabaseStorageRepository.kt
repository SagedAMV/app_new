package com.unihub.app.data.supabase

import com.unihub.app.data.cloud.CloudStorageConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * المستودع الذي تستهلكه طبقة المزامنة.
 *
 * كان `AppwriteStorageRepository` يرث من `AppwriteStorageTransport` لتوفير
 * نقطة حقن واحدة. نفس النمط هنا: صنف واحد يجمع إحداثيات الاتصال مع طبقة
 * النقل، فتتغيّر طبقة `data/cloud` **بصفر أسطر**.
 */
@Singleton
class SupabaseStorageRepository @Inject constructor(
    authorization: SupabaseStorageAuthorization,
    http: SupabaseHttp
) : SupabaseStorageTransport(
    CloudStorageConnection(
        endpointUrl = SupabaseConfig.PROJECT_URL,
        projectId = SupabaseConfig.PROJECT_REF,
        bucketId = SupabaseConfig.STORAGE_BUCKET_ID
    ),
    authorization,
    http
)
