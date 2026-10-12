package com.unihub.app.data.appwrite

/** Minimal boundary allowing protocol tests to use a fake grant without any real cloud traffic. */
interface StorageAuthorizer {
    suspend fun grant(): StorageGrant
    fun requireCurrent(grant: StorageGrant)
    fun clear()
}
