package io.openruntimes.kotlin

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Regression for Open Runtimes' dependency-linking step. This is not a live deployment test.
 * Source: open-runtimes/open-runtimes@da714e707c181e6e8e4ab5f1a5649d2cde8f4260,
 * runtimes/kotlin/versions/latest/hooks/build-prepare.sh.
 * It moves user *.gradle* scripts beside the runtime build.gradle and appends apply-from.
 */
class BridgePackagingTest {
    @Rule @JvmField val temp = TemporaryFolder()
    private val source: File get() = File(System.getProperty("bridge.source.dir", "auth-bridge"))

    @Test fun deploymentMustNotOverwriteRuntimeBuildFiles() {
        assertTrue("Function source directory is missing: $source", source.isDirectory)
        val reserved = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val collisions = source.listFiles().orEmpty().filter { it.name in reserved }
        assertTrue("Reserved runtime build filenames: ${collisions.map { it.name }}", collisions.isEmpty())
        assertTrue("Use dependencies.gradle for function dependencies", File(source, "dependencies.gradle").isFile)
    }

    @Test fun currentPackagePreservesRuntimeBuildAndBuildJarTask() {
        val runtime = linkDependencyScripts(source.listFiles().orEmpty().filter { it.name.contains(".gradle") })
        assertTrue("Function dependencies overwrote the runtime build", runtime.readText().contains(SENTINEL))
        assertTrue("Runtime buildJar task was lost", runtime.readText().contains("tasks.register(\"buildJar\")"))
    }

    @Test fun dependencyImportDoesNotReferenceRuntimeBuildItself() {
        val runtime = linkDependencyScripts(source.listFiles().orEmpty().filter { it.name.contains(".gradle") })
        val text = runtime.readText()
        assertFalse("Recursive build.gradle import", text.contains("apply from: \"./build.gradle\""))
        assertTrue("Dependency script was not linked", text.contains("apply from: \"./dependencies.gradle\""))
    }

    @Test fun jsonDependencyIsPreservedAndLinkedExactlyOnce() {
        val runtime = linkDependencyScripts(source.listFiles().orEmpty().filter { it.name.contains(".gradle") })
        val dependency = File(runtime.parentFile, "dependencies.gradle")
        assertTrue(dependency.isFile)
        assertTrue(dependency.readText().contains("org.json:json:20240303"))
        assertEquals(1, runtime.readLines().count { it == "apply from: \"./dependencies.gradle\"" })
    }

    @Test fun legacyFilenameReproducesRuntimeOverwriteAndRecursiveImport() {
        val legacy = temp.newFolder("legacy").resolve("build.gradle")
        legacy.writeText("dependencies { implementation \"org.json:json:20240303\" }\n")
        val runtime = linkDependencyScripts(listOf(legacy))
        assertFalse(runtime.readText().contains(SENTINEL))
        assertFalse(runtime.readText().contains("tasks.register(\"buildJar\")"))
        assertTrue(runtime.readText().contains("apply from: \"./build.gradle\""))
    }

    @Test fun deploymentStillContainsItsEntrypointAndSharedSources() {
        for (name in listOf("Index.kt", "BridgeService.kt", "AppwriteRestApi.kt")) {
            val file = File(source, name)
            assertTrue("Missing deployed source: $name", file.isFile)
            assertTrue("Incorrect runtime package: $name", file.readText().startsWith("package io.openruntimes.kotlin"))
        }
    }

    private fun linkDependencyScripts(scripts: List<File>): File {
        val server = temp.newFolder()
        val upload = File(server, "src/main/java/io/openruntimes/java").apply { mkdirs() }
        val runtime = File(server, "build.gradle").apply {
            writeText("$SENTINEL\nplugins { id 'org.jetbrains.kotlin.jvm' }\ntasks.register(\"buildJar\") {}\n")
        }
        scripts.forEach { it.copyTo(File(upload, it.name)) }
        upload.listFiles().orEmpty().filter { it.isFile && it.name.contains(".gradle") }.sortedBy { it.name }.forEach { script ->
            val name = script.name
            Files.move(script.toPath(), File(server, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            runtime.appendText("apply from: \"./$name\"\n")
        }
        return runtime
    }

    companion object { private const val SENTINEL = "// OPEN_RUNTIMES_BUILD_SENTINEL" }
}
