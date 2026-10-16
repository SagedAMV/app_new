package com.unihub.app.data.appwrite

/** Public identifiers only. Server API keys must never be added to this object or to BuildConfig. */
object AppwriteConfig {
    const val AUTH_ENDPOINT = "https://nyc.cloud.appwrite.io/v1"
    const val AUTH_PROJECT_ID = "6acd036f0022a495a9df"
    const val STORAGE_ENDPOINT = "https://nyc.cloud.appwrite.io/v1"
    const val STORAGE_PROJECT_ID = "6acd0a6800189ccc164a"
    const val STORAGE_BUCKET_ID = "6acd11520015520ba016"
    // Appwrite Cloud's Storage API rejects values above 50,000,000 bytes.
    // Keep CloudFileRules.MAX_UPLOAD_FILE_BYTES aligned with this server-side ceiling.
    const val STORAGE_MAX_FILE_SIZE_BYTES = 50_000_000L

    // Deploy the bridge in the AUTH project with this explicit function ID.
    const val AUTH_BRIDGE_FUNCTION_ID = "unihub-auth-bridge"

    // CANONICAL label names — must stay byte-identical to (1) the deployed bridge
    // (service/appwrite/node-bridge/src/main.js) and (2) the bucket $permissions on
    // Appwrite Cloud. The deployed runtime uses the underscore-less form; do NOT
    // "restore" underscores here — that drifts from the cloud and silently strips
    // non-admin users' permissions in CloudAuthRules.fromAppwriteUser().
    // Verified live against the bucket policy on 2026-10-15.
    const val DOWNLOAD_LABEL = "unihubdownload"
    const val UPLOAD_LABEL = "unihubupload"
    const val MODIFY_LABEL = "unihubmodify"
    const val ADMIN_LABEL = "admin"
    const val STORAGE_TOKEN_TTL_SECONDS = 300L
}
