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
 * نسخة اختبار مهملة. نجح الفتح؟ نغلقها ويكمل التطبيق طبيعياً. فشل؟ نحذف الملف
 * مع ملحقاته ويُنشئ Room قاعدة نظيفة عند أول استخدام. التطبيق شخصي وبياناته
 * محلية، فالإعادة النظيفة أفضل من حلقة كراش لا نهائية عند الإقلاع.
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
            wipeDatabaseFiles(context, dbFile)
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
