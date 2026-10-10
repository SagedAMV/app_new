package com.unihub.app.data.storage

import java.io.File
import java.io.IOException

/** حجز ذري باسم قابل للعرض، لا فحص exists() ثم كتابة قد تتصادم مع عملية أخرى. */
internal fun reserveUniqueFile(directory: File, base: String, extension: String): File {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("تعذّر إنشاء مجلد التخزين")

    // This allocator is the final trust boundary for every file creation path. Names may
    // originate from a content provider, cloud manifest, or old backup; never allow them
    // to introduce path separators, control characters, or traversal components.
    val safeBase = buildString(base.length) {
        base.forEach { char ->
            when {
                char.code < 0x20 || char.code == 0x7f -> Unit
                char in "/\\:*?\"<>|" -> append('_')
                else -> append(char)
            }
        }
    }.trim().take(120).ifBlank { "file" }
    val safeExtension = extension.trim().trimStart('.')
        .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        .take(16)
    val suffix = if (safeExtension.isBlank()) "" else ".$safeExtension"
    var counter = 0
    while (true) {
        val candidate = File(directory, safeBase + if (counter == 0) suffix else " ($counter)$suffix")
        if (candidate.canonicalFile.parentFile == directory.canonicalFile && candidate.createNewFile()) return candidate
        counter++
        if (counter == Int.MAX_VALUE) throw IOException("تعذّر حجز اسم ملف فريد")
    }
}
