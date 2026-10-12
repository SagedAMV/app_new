package com.unihub.app.data.cloud

import com.unihub.app.data.appwrite.AppwriteConfig
import com.unihub.app.data.appwrite.AppwriteStorageAuthorization
import com.unihub.app.data.appwrite.AppwriteStorageTransport
import com.unihub.app.di.StorageAppwrite
import io.appwrite.Client
import javax.inject.Inject
import javax.inject.Singleton

/** Only this qualified client supplies the endpoint/project for every file operation. */
@Singleton
class AppwriteStorageRepository @Inject constructor(
    @StorageAppwrite storageAppwriteClient: Client,
    authorization: AppwriteStorageAuthorization
) : AppwriteStorageTransport(
    CloudStorageConnection(
        endpointUrl = storageAppwriteClient.endpoint,
        projectId = storageAppwriteClient.config["project"].orEmpty(),
        bucketId = AppwriteConfig.STORAGE_BUCKET_ID
    ),
    authorization
)
