package com.unihub.app.data.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class FileTargetAllocatorTest {
    @Test fun concurrentImportsCannotOverwriteEachOther() {
        val dir = Files.createTempDirectory("unique_targets_").toFile()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val names = pool.invokeAll((1..80).map { id -> Callable {
                val file = reserveUniqueFile(dir, "محاضرة", "pdf")
                file.writeText(id.toString())
                file.name
            } }).map { it.get() }
            assertEquals(80, names.toSet().size)
            assertEquals(80, dir.listFiles()!!.map { it.readText() }.toSet().size)
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
    }
    @Test fun existingContentIsNeverReplaced() {
        val dir = Files.createTempDirectory("unique_targets_").toFile()
        try {
            val original = File(dir, "ملف.pdf").apply { writeText("keep me") }
            val reserved = reserveUniqueFile(dir, "ملف", "pdf")
            assertEquals("ملف (1).pdf", reserved.name)
            assertEquals("keep me", original.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun noExtensionDoesNotAddTrailingDot() {
        val dir = Files.createTempDirectory("unique_targets_").toFile()
        try { assertEquals("README", reserveUniqueFile(dir, "README", "").name) }
        finally { dir.deleteRecursively() }
    }
}
