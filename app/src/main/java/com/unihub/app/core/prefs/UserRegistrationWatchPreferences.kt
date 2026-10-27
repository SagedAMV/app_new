package com.unihub.app.core.prefs

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userRegistrationWatchDataStore by preferencesDataStore(
    name = "unihub_user_registration_watch",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * يحفظ فقط معرّفات (UUID) المستخدمين الذين "رآهم" المشرف سابقًا — لا بريد ولا
 * كلمات مرور ولا أي بيانات حساسة، لذلك لا داعي لخزنة مشفّرة كـ[CloudAuthPreferences].
 * الهدف الوحيد: تمكين [com.unihub.app.data.auth.NewUserWatchRules] من معرفة من
 * هو "جديد" منذ آخر فحص، لإظهار إشعار محلي صادق بدل التخمين.
 */
@Singleton
class UserRegistrationWatchPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private val knownIdsKey = stringSetPreferencesKey("known_user_ids")

    suspend fun knownUserIds(): Set<String> = withContext(Dispatchers.IO) {
        context.userRegistrationWatchDataStore.data.first()[knownIdsKey].orEmpty()
    }

    suspend fun saveKnownUserIds(ids: Set<String>) = withContext(Dispatchers.IO) {
        context.userRegistrationWatchDataStore.edit { it[knownIdsKey] = ids }
    }
}
