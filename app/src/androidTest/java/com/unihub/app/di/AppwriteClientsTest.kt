package com.unihub.app.di

import androidx.test.platform.app.InstrumentationRegistry
import com.unihub.app.data.appwrite.AppwriteConfig
import org.junit.Assert.*
import org.junit.Test

/** Client construction/configuration only. No request is sent to either real project. */
class AppwriteClientsTest {
    @Test fun twoFactoriesCreateIndependentCorrectlyRoutedClients() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = AppwriteModule.provideAuthAppwriteClient(context)
        val storage = AppwriteModule.provideStorageAppwriteClient(context)
        assertNotSame(auth, storage)
        assertEquals("6acd036f0022a495a9df", auth.config["project"])
        assertEquals("6acd0a6800189ccc164a", storage.config["project"])
        assertEquals(AppwriteConfig.AUTH_ENDPOINT, auth.endpoint)
        assertEquals(AppwriteConfig.STORAGE_ENDPOINT, storage.endpoint)
    }
    @Test fun credentialsAreNotCopiedBetweenProjectsEvenWithOneEndpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = AppwriteModule.provideAuthAppwriteClient(context)
        val storage = AppwriteModule.provideStorageAppwriteClient(context)
        auth.setJWT("synthetic-auth-token")
        auth.setSession("synthetic-auth-session")
        assertNull(storage.config["jWT"])
        assertNull(storage.config["session"])
        storage.setJWT("synthetic-storage-token")
        assertEquals("synthetic-auth-token", auth.config["jWT"])
        assertEquals("synthetic-storage-token", storage.config["jWT"])
    }
}
