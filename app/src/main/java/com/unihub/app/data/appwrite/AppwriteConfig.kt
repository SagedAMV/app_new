package com.unihub.app.data.appwrite

/** Public identifiers only. Server API keys must never be added to this object or to BuildConfig. */
object AppwriteConfig {
    const val AUTH_ENDPOINT = "https://nyc.cloud.appwrite.io/v1"
    const val AUTH_PROJECT_ID = "6acd036f0022a495a9df"
    const val STORAGE_ENDPOINT = "https://nyc.cloud.appwrite.io/v1"
    const val STORAGE_PROJECT_ID = "6acd0a6800189ccc164a"
    const val STORAGE_BUCKET_ID = "6acd11520015520ba016"

    // Deploy service/appwrite/auth-bridge in the AUTH project with this explicit function ID.
    const val AUTH_BRIDGE_FUNCTION_ID = "unihub-auth-bridge"
    const val DOWNLOAD_LABEL = "unihub_download"
    const val UPLOAD_LABEL = "unihub_upload"
    const val MODIFY_LABEL = "unihub_modify"
    const val ADMIN_LABEL = "admin"
    const val STORAGE_TOKEN_TTL_SECONDS = 300L
}
