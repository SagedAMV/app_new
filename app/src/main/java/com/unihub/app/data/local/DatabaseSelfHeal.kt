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
 * مع ملحقاته (`-wal`/`-shm`) ويُنشئ Room قاعدة نظيفة عند أول استخدام. التطبيق
 * شخصي وبياناته محلية، فالإعادة النظيفة أفضل من حلقة كراش لا نهائية عند الإقلاع.
 */
object DatabaseSelfHeal {

    private const val TAG = "DatabaseSelfHeal"

    /** يُستدعى مرة واحدة في [com.unihub.app.UniHubApplication.onCreate] */
    fun ensureHealthyDatabase(context: Context) {
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
            context.deleteDatabase(UniHubDatabase.NAME)
            deleteJournalFiles(dbFile)
        }
    }

    /** حذف ملحقات SQLite التي قد تبقى بعد فتح فاشل */
    private fun deleteJournalFiles(dbFile: File) {
        runCatching {
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }
}
