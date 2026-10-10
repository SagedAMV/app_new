package com.unihub.app.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import java.io.File

/**
 * التضميد الذاتي لقاعدة البيانات عند الإقلاع.
 *
 * مشكلة الكراش الجذرية: التطبيق تطوّر عبر بنات تطوير متعاقبة على نفس الجهاز،
 * وقد يبقى ملف [UniHubDatabase.NAME] الذي أنشأته بِناءات سابقة بمخطط (هوية مخطط
 * مختلفة) غير متوافق مع الكود الحالي. في هذه الحالة يرمي Room استثناء
 * `IllegalStateException` عند أول فتح — أي كراش فوري عند فتح التطبيق.
 *
 * ملاحظة مهمة: `fallbackToDestructiveMigration()` لا يغطي هذه الحالة؛ هو يعمل فقط
 * عند تغيّر رقم الإصدار، بينما عدم تطابق هوية المخطط بنفس رقم الإصدار يُرمي
 * الاستثناء مباشرة من `RoomOpenHelper.checkIdentity`.
 *
 * الحل: قبل أن يلمس أي مكوّن (Hilt/ViewModel) القاعدة، نفتح الملف فعلياً عبر
 * نسخة اختبار مهملة. نجح الفتح؟ نغلقها ويكمل التطبيق طبيعياً. فشل؟ نحاول أولاً
 * حفظ قاعدة البيانات وملفات SQLite المرافقة في مجلد إنقاذ خاص بالتطبيق، ثم نعيد
 * إنشاء القاعدة النشطة فقط بعد التحقق من النسخة المحفوظة. إنقاذ البيانات يسبق
 * الإصلاح التلقائي لتقليل احتمال الفقد غير القابل للاسترداد.
 *
 * تحصين الجلسة الحالية (ما أُضيف على النسخة السابقة):
 * 1) شبكة أمان خارجية حول الفحص كله — المُداوي نفسه لا يجوز أن يكون سبب كراش
 *    الإقلاع مهما حدث داخله.
 * 2) التحقق من نجاح الحذف: `Context.deleteDatabase` يعيد `false` بصمت إن فشل
 *    (ملف مقفول/قيد الاستخدام)، وبقاء الملف التالف يعني عودة الكراش في كل
 *    إقلاع — لذلك نتحقق ونكمل بالحذف المباشر للملف وملحقاته
 *    (`-wal`/`-shm`/`-journal`) مع تسجيل النتيجة.
 */
object DatabaseSelfHeal {

    private const val TAG = "DatabaseSelfHeal"

    /** يُستدعى مرة واحدة في [com.unihub.app.UniHubApplication.onCreate] */
    fun ensureHealthyDatabase(context: Context) {
        try {
            healIfNeeded(context)
        } catch (unexpected: Throwable) {
            // المُداوي لا يُسقط التطبيق أبداً: أي مفاجأة غير متوقعة تُسجَّل
            // ويُكمل التطبيق إقلاعه (أسوأ ما يحدث هنا = سلوك ما قبل الاستشفاء).
            Log.e(TAG, "فحص قاعدة البيانات تعذّر — يُكمل التطبيق الإقلاع", unexpected)
        }
    }

    private fun healIfNeeded(context: Context) {
        val dbFile = context.getDatabasePath(UniHubDatabase.NAME)
        // تثبيت جديد: لا يوجد ملف أصلاً — لا حاجة لأي فحص
        if (!dbFile.exists()) return

        val probe = Room.databaseBuilder(context, UniHubDatabase::class.java, UniHubDatabase.NAME)
            .build()
        try {
            // فتح فعلي: هنا يتحقق Room من سلامة الملف وهوية المخطط
            probe.openHelper.writableDatabase
            probe.close()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "قاعدة بيانات غير متوافقة أو تالفة عند الإقلاع — ستُعاد بنيتها من جديد. " +
                    "السبب: ${error.message}"
            )
            runCatching { probe.close() }
            // Never destroy the only copy of a user's academic data. Preserve the database
            // and SQLite sidecars first; only create a fresh DB after a verified copy exists.
            if (preserveDatabaseFiles(context, dbFile)) {
                wipeDatabaseFiles(context, dbFile)
            } else {
                Log.e(TAG, "لم تُحذف قاعدة البيانات القديمة لأن إنشاء نسخة إنقاذ موثوقة فشل؛ يلزم استرداد يدوي")
            }
        }
    }


    /**
     * Copies the main database and any SQLite sidecars to app-private recovery storage.
     * Recovery copies are kept separate from the active DB and the two newest snapshots
     * are retained. If any copy fails or has a different size, the active database is left
     * untouched rather than risking irreversible loss.
     */
    private fun preserveDatabaseFiles(context: Context, dbFile: File): Boolean {
        if (!dbFile.isFile) return false
        val recoveryRoot = File(context.filesDir, "database-recovery")
        if (!recoveryRoot.exists() && !recoveryRoot.mkdirs()) return false
        val snapshot = File(recoveryRoot, "unihub-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}")
        if (!snapshot.mkdirs()) return false

        val sources = listOf(
            dbFile to "unihub.sqlite",
            sibling(dbFile, "-wal") to "unihub.sqlite-wal",
            sibling(dbFile, "-shm") to "unihub.sqlite-shm",
            sibling(dbFile, "-journal") to "unihub.sqlite-journal"
        ).filter { it.first.isFile }
        if (sources.none { it.first.absoluteFile == dbFile.absoluteFile }) {
            snapshot.deleteRecursively()
            return false
        }

        return try {
            sources.forEach { (source, name) ->
                val destination = File(snapshot, name)
                source.copyTo(destination, overwrite = false)
                if (!destination.isFile || destination.length() != source.length()) {
                    throw java.io.IOException("Database recovery copy verification failed")
                }
            }
            File(snapshot, "RECOVERY_INFO.txt").writeText(
                "UniHub database recovery snapshot\n" +
                    "Created: ${System.currentTimeMillis()}\n" +
                    "The database was not readable by the current app build. Keep this folder until data recovery is confirmed.\n",
                Charsets.UTF_8
            )
            // Keep the latest two successful snapshots only; never trim until this one is verified.
            recoveryRoot.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("unihub-") && it != snapshot }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(1)
                ?.forEach { old -> runCatching { old.deleteRecursively() } }
            Log.w(TAG, "حُفظت نسخة إنقاذ من قاعدة البيانات في ${snapshot.absolutePath}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "تعذّر التحقق من نسخة إنقاذ قاعدة البيانات؛ ستبقى القاعدة الأصلية دون حذف", error)
            runCatching { snapshot.deleteRecursively() }
            false
        }
    }

    /**
     * حذف القاعدة مع كل ملحقاتها والتحقق من نجاح الحذف فعلياً.
     * نبدأ بـ [Context.deleteDatabase] (الطريق النظامي)، وإن بقي أي ملف على القرص
     * نحذفه مباشرة — بقاء أي جزء من القاعدة التالفة يعيد الكراش التالي.
     */
    private fun wipeDatabaseFiles(context: Context, dbFile: File) {
        runCatching { context.deleteDatabase(UniHubDatabase.NAME) }

        val leftovers = listOf(
            dbFile,
            sibling(dbFile, "-wal"),
            sibling(dbFile, "-shm"),
            sibling(dbFile, "-journal")
        ).filter { it.exists() }

        if (leftovers.isEmpty()) return

        leftovers.forEach { file -> runCatching { file.delete() } }
        val remaining = leftovers.count { it.exists() }
        if (remaining > 0) {
            // فشل الحذف حتى بالمباشر — يُسجَّل بخطورة لأنه يعني احتمال تكرار الكراش
            Log.e(TAG, "تعذّر حذف $remaining من ملفات القاعدة — قد يتكرر كراش الإقلاع")
        } else {
            Log.w(TAG, "حُذفت ملفات القاعدة مباشرة بعد فشل deleteDatabase")
        }
    }

    private fun sibling(dbFile: File, suffix: String): File =
        File(dbFile.absolutePath + suffix)
}
