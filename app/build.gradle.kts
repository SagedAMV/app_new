plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.unihub.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unihub.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // توقيع شخصي للنسخة المصغّرة — تطبيق غير منشور (البيانات في keystore/unihub-release.jks)
            storeFile = file("keystore/unihub-release.jks")
            storePassword = "unihub2026"
            keyAlias = "unihub"
            keyPassword = "unihub2026"
        }
    }

    buildTypes {
        release {
            // ⚠️ قرار متعمّد ونهائي — لا تُفعّل التصغير مجدداً دون قراءة هذا كاملاً:
            //
            // السبب الجذري لكراش الإقلاع (تشخيص مؤكَّد بجلسات سابقة على جهاز أندرويد 12
            // حقيقي، commits a53523b/6d20ade/01b0753): نسخة Release المصغّرة (R8 +
            // تقليص الموارد) كانت تُسقط التطبيق فوراً عند أول فتح. تحصين قواعد proguard
            // (kotlinx.serialization) عالج جزءاً من الخطر لكنه لم يُختبر فعلياً على جهاز
            // حقيقي عند إعادة تفعيل التصغير في commit bc1a26b — فقط تحقّق ثابت من بقاء
            // الأصناف داخل الـ dex، وهو غير كافٍ للتأكد من سلامة كل مسار انعكاسي
            // (reflection) يستخدمه Room / WorkManager / Navigation في وقت التشغيل الفعلي.
            //
            // القرار: تطبيق شخصي غير منشور على متجر — لا فائدة عملية من تصغير الحجم
            // (لا حدود توزيع، لا قياسات تنزيل) بينما الخطر (كراش إقلاع كامل) كارثي.
            // العائد لا يبرر المخاطرة إطلاقاً. لذلك: بلا تصغير ولا تقليص موارد بشكل دائم.
            // قواعد proguard-rules.pro أُبقيت ومُتّنت كطبقة أمان إضافية فقط تحسّباً،
            // وليست ترخيصاً لإعادة تفعيل isMinifyEnabled.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.splashscreen)

    // Compose (BOM يوحّد إصدارات كل مكتبات Compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)

    // تنقّل آمن الأنواع (Type-Safe Navigation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager لتذكيرات المهام والامتحانات
    implementation(libs.androidx.work.runtime)

    // CameraX — التقاط الصور من داخل التطبيق (المكتبة الرسمية الموصى بها من Google)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // اختبارات الوحدة
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // أدوات التطوير
    debugImplementation(libs.androidx.compose.ui.tooling)
}
