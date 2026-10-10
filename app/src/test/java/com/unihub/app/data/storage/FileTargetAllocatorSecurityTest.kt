package com.unihub.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileTargetAllocatorSecurityTest {
    @Test fun pathSeparatorsAndTraversalAreNeutralized() {
        val root = Files.createTempDirectory("unihub-file-target-test").toFile()
        try {
            val library = java.io.File(root, "library")
            val target = reserveUniqueFile(library, "../../escape/evil", "../../outside")
            assertEquals(library.canonicalFile, target.canonicalFile.parentFile)
            assertTrue(target.isFile)
            assertTrue('/' !in target.name && '\\' !in target.name)
            assertTrue('/' !in target.extension && '\\' !in target.extension)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun duplicateNamesReserveDifferentFiles() {
        val root = Files.createTempDirectory("unihub-file-target-unique").toFile()
        try {
            val library = java.io.File(root, "library")
            val first = reserveUniqueFile(library, "Document", "pdf")
            val second = reserveUniqueFile(library, "Document", "pdf")
            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertTrue(first.isFile && second.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
