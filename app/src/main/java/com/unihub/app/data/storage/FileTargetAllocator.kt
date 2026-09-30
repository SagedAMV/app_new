package com.unihub.app.data.storage

import java.io.File
import java.io.IOException

/** حجز ذري باسم قابل للعرض، لا فحص exists() ثم كتابة قد تتصادم مع عملية أخرى. */
internal fun reserveUniqueFile(directory: File, base: String, extension: String): File {
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("تعذّر إنشاء مجلد التخزين")
    val suffix = if (extension.isBlank()) "" else ".$extension"
    var counter = 0
    while (true) {
        val candidate = File(directory, base + if (counter == 0) suffix else " ($counter)$suffix")
        if (candidate.createNewFile()) return candidate
        counter++
    }
}
