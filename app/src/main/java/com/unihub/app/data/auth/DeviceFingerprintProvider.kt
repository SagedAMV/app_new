package com.unihub.app.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.unihub.app.core.prefs.CloudAuthPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مزود بصمة الجهاز ومعلوماته التعريفية.
 * يربط كل حساب مستخدم بجهاز واحد كما في الأنظمة البنكية، ويوفر للمشرف
 * تفاصيل الجهاز (الشركة المصنعة، الطراز، إصدار أندرويد، ومعرف البصمة) عند طلب تغيير الجهاز.
 */
@Singleton
class DeviceFingerprintProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authPreferences: CloudAuthPreferences
) {
    @Volatile
    private var cachedDevice: BoundDeviceInfo? = null

    @SuppressLint("HardwareIds")
    suspend fun getDeviceInfo(): BoundDeviceInfo {
        cachedDevice?.let { return it }

        val rawAndroidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim().orEmpty()

        val effectiveId = if (rawAndroidId.length >= 6 && rawAndroidId != "9774d56d682e549c") {
            rawAndroidId
        } else {
            authPreferences.getOrCreateFallbackSeed()
        }

        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val brand = Build.BRAND?.trim().orEmpty()
        val device = Build.DEVICE?.trim().orEmpty()
        val osVersion = Build.VERSION.RELEASE?.trim().orEmpty()

        val fingerprint = CloudAuthRules.computeDeviceFingerprint(
            androidId = effectiveId,
            manufacturer = manufacturer,
            model = model,
            brand = brand,
            device = device
        )

        val readableName = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "جهاز أندرويد" }

        return BoundDeviceInfo(
            fingerprint = fingerprint,
            deviceName = readableName,
            manufacturer = manufacturer,
            model = model,
            androidVersion = osVersion,
            boundAt = System.currentTimeMillis()
        ).also { cachedDevice = it }
    }
}
