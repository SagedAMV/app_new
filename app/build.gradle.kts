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
            // ⚠️ قرار متعمّد (مُحدَّث هذه الجلسة) — التصغير مُفعَّل عمداً لأنه صار آمناً:
            //
            // التاريخ: جلسة سابقة (commit 932e6a0) عطّلت التصغير هرباً من كراش إقلاع
            // على جهاز حقيقي لم يُعرف سببه الجذري وقتها. هذه الجلسة شخّصت السبب
            // الجذري وأصلحته من الجذر بدل الهروب منه:
            //
            //   كان R8 full mode (الافتراضي في AGP 8.7) يُسقط «المُنشئ بلا وسائط»
            //   للتطبيقات المولَّدة من Room (*_Impl) — وأهمها قاعدة WorkManager
            //   الداخلية androidx.work.impl.WorkDatabase_Impl — التي تُحمَّل وقت
            //   التشغيل عبر Class.forName + newInstance() فلا يراها R8. لذلك نجح
            //   الفحص القديم «الصنف موجود في الـ dex» بينما انهار الجهاز بـ
            //   NoSuchMethodException — الوجود في الـ dex لا يعني سلامة المُنشئ.
            //
            // الإصلاح: قواعد شاملة في proguard-rules.pro (قسم 1) تُبقي مُنشئات كل
            // RoomDatabase ومولّداتها — الإصلاح نفسه الذي اعتمدته Google رسمياً في
            // Room 2.7.0-beta01 (b/392657750) — مع تحصين مُهيّئ WorkManager في
            // androidx.startup وقواعد kotlinx.serialization الرسمية.
            //
            // أي تعديل هنا مستقبلاً: اقرأ تقرير_جلسة_تفعيل_النسخة_المصغرة.md أولاً.
            isMinifyEnabled = true
            isShrinkResources = true
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
