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
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    val bridgeSources = layout.projectDirectory.dir("auth-bridge")
    systemProperty("bridge.source.dir", bridgeSources.asFile.absolutePath)
    // Gradle scripts are deployment inputs, not Kotlin compiler inputs. Changes must rerun packaging tests.
    inputs.files(fileTree(bridgeSources) { include("*.gradle*") })
}
