plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("auth-bridge")
        // RuntimeContext/RuntimeOutput are supplied by Appwrite's Kotlin runtime at deployment.
        kotlin.exclude("Index.kt")
    }
}

dependencies {
    implementation("org.json:json:20240303")
}
