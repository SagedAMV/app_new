package com.unihub.app.di

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.unihub.app.data.appwrite.AppwriteConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.appwrite.Client
import io.appwrite.services.Account
import io.appwrite.services.Functions
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthAppwrite

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StorageAppwrite

/** No unqualified Client is provided: accidentally crossing the project boundary fails Hilt compilation. */
@Module
@InstallIn(SingletonComponent::class)
object AppwriteModule {
    @Provides
    @Singleton
    @AuthAppwrite
    fun provideAuthAppwriteClient(@ApplicationContext context: Context): Client =
        Client(ProjectContext(context, AppwriteConfig.AUTH_PROJECT_ID))
            .setEndpoint(AppwriteConfig.AUTH_ENDPOINT)
            .setProject(AppwriteConfig.AUTH_PROJECT_ID)

    @Provides
    @Singleton
    @StorageAppwrite
    fun provideStorageAppwriteClient(@ApplicationContext context: Context): Client =
        Client(ProjectContext(context, AppwriteConfig.STORAGE_PROJECT_ID))
            .setEndpoint(AppwriteConfig.STORAGE_ENDPOINT)
            .setProject(AppwriteConfig.STORAGE_PROJECT_ID)

    @Provides
    @Singleton
    @AuthAppwrite
    fun provideAuthAccount(@AuthAppwrite authAppwriteClient: Client): Account = Account(authAppwriteClient)

    @Provides
    @Singleton
    @AuthAppwrite
    fun provideAuthFunctions(@AuthAppwrite authAppwriteClient: Client): Functions = Functions(authAppwriteClient)

    /** The SDK otherwise uses one preference-file name for all clients on a device. */
    private class ProjectContext(context: Context, private val projectId: String) : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("appwrite_${projectId}_$name", mode)
    }
}
