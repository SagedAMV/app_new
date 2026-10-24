package com.unihub.app.di

import com.unihub.app.data.supabase.SupabaseConfig
import com.unihub.app.data.supabase.SupabaseHttp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * تهيئة عميل Supabase.
 *
 * يوفّر **مفتاح نشر** (عام) فقط. لا مفتاح إداري في التطبيق إطلاقًا: مفاتيح
 * الإدارة تُستعمل في بيئة الإدارة (ترحيلات القاعدة، سكربتات الفحص) ولا
 * تدخل حزمة التطبيق.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseHttp(): SupabaseHttp =
        SupabaseHttp(SupabaseConfig.PROJECT_URL, SupabaseConfig.PUBLISHABLE_KEY)
}
