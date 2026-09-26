package com.unihub.app.feature.share

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unihub.app.core.validation.InputValidationException
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FileKind
import com.unihub.app.data.repository.FileRepository
import com.unihub.app.data.storage.FileStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** ملف شاركه تطبيق خارجي وينتظر اختيار المجلد الوجهة داخل التطبيق */
data class InboxItem(
    val id: String,
    val name: String,
    val extension: String,
    val mimeType: String,
    val size: Long,
    val filePath: String,
    val receivedAt: Long
) {
    val kind: FileKind get() = FileKind.fromExtension(extension)
}

/** نتيجة استقبال دفعة مشاركة: ما نجح وما فشل مع أسباب عربية جاهزة للعرض */
data class InboxIngestResult(
    val accepted: Int,
    val rejectedMessages: List<String>
)

private val Context.shareInboxDataStore by preferencesDataStore(name = "unihub_share_inbox")

/**
 * «صندوق المشاركة» — ملفات واردة من قائمة مشاركة النظام تنتظر أن يختار
 * المستخدم مجلدها الوجهة.
 *
 * لماذا لا تُدرج مباشرة في قاعدة البيانات؟ لأن ملفاً بلا مجلد يظهر في
 * المستوى الجذر، والمطلوب أن تبقى الملفات المشتركة معلقة حتى يقرر المستخدم
 * أين يضعها. لذلك تُنسخ النسخ الفعلية فوراً إلى مكتبة التخزين (أذونات
 * content:// مؤقتة وتموت مع النشاط المُرسِل) بينما تُحفظ السجلات هنا في
 * DataStore — تبقى بعد إغلاق التطبيق وحتى إعادة تشغيل الجهاز.
 */
@Singleton
class ShareInbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileStorage: FileStorage,
    private val fileRepository: FileRepository
) {

    private val itemsKey = stringPreferencesKey("inbox_items")

    /** قائمة الصندوق الحية — تظهر فوراً في كل شاشات الملفات */
    val items: Flow<List<InboxItem>> = context.shareInboxDataStore.data
        .map { prefs -> parseItems(prefs[itemsKey].orEmpty()) }

    /**
     * استقبال ملفات المشاركة: نسخ فعلي فوري لكل ملف إلى مكتبة التطبيق ثم
     * تسجيله في الصندوق. كل ملف يعالج مستقلاً — فشل أحدها لا يسقط البقية
     * (مهم عند مشاركة أعداد كبيرة من الملفات).
     */
    suspend fun ingest(uris: List<Uri>): InboxIngestResult = withContext(Dispatchers.IO) {
        var accepted = 0
        val rejectedMessages = mutableListOf<String>()

        uris.forEach { uri ->
            runCatching {
                val imported = fileStorage.import(uri, "*/*")
                val item = InboxItem(
                    id = java.util.UUID.randomUUID().toString(),
                    name = imported.displayName,
                    extension = imported.extension,
                    mimeType = imported.mimeType,
                    size = imported.size,
                    filePath = imported.absolutePath,
                    receivedAt = System.currentTimeMillis()
                )
                appendItem(item)
                accepted++
            }.onFailure { error ->
                rejectedMessages += when (error) {
                    is InputValidationException -> error.error.message
                    is SecurityException -> "انتهى إذن الوصول لأحد الملفات — أعد مشاركته"
                    is IOException -> "تعذّرت قراءة أحد الملفات المشتركة"
                    else -> "تعذّر حفظ أحد الملفات المشتركة"
                }
                Log.w(TAG, "رفض ملف وارد من المشاركة", error)
            }
        }
        InboxIngestResult(accepted, rejectedMessages)
    }

    /**
     * وضع كل ملفات الصندوق داخل مجلد محدد: تُدرج سجلاتها في القاعدة بالمجلد
     * الوجهة (النسخ الفعلية موجودة أصلاً في المكتبة المسطحة — لا حركة ملفات
     * على القرص) ثم تُفرغ الصندوق. يعيد معرّفات السجلات الجديدة لإبرازها في
     * الواجهة بانميشن الدخول.
     */
    suspend fun placeAll(targetFolderId: Long?): List<Long> {
        val current = readItemsOnce()
        if (current.isEmpty()) return emptyList()
        val placedIds = mutableListOf<Long>()
        current.forEach { item ->
            runCatching {
                val entity = fileRepository.insertSharedFile(
                    FileEntity(
                        name = item.name,
                        extension = item.extension,
                        kind = item.kind,
                        mimeType = item.mimeType,
                        size = item.size,
                        folderId = targetFolderId,
                        filePath = item.filePath
                    )
                )
                placedIds += entity.id
            }.onFailure { Log.e(TAG, "تعذّر وضع الملف المشترك ${item.name}", it) }
        }
        clearItems()
        return placedIds
    }

    /** التخلي عن ملف واحد: حذف نسخته الفعلية وإزالته من الصندوق */
    suspend fun remove(item: InboxItem) {
        withContext(Dispatchers.IO) {
            runCatching { fileStorage.delete(item.filePath) }
        }
        removeItem(item.id)
    }

    // ─── التخزين في DataStore ───────────────────────────────────────────

    private suspend fun readItemsOnce(): List<InboxItem> =
        parseItems(
            context.shareInboxDataStore.data
                .map { it[itemsKey].orEmpty() }
                .first()
        )

    private suspend fun appendItem(item: InboxItem) {
        context.shareInboxDataStore.edit { prefs ->
            val list = parseItems(prefs[itemsKey].orEmpty()).toMutableList()
            list += item
            prefs[itemsKey] = serializeItems(list)
        }
    }

    private suspend fun removeItem(id: String) {
        context.shareInboxDataStore.edit { prefs ->
            val list = parseItems(prefs[itemsKey].orEmpty()).filterNot { it.id == id }
            prefs[itemsKey] = serializeItems(list)
        }
    }

    private suspend fun clearItems() {
        context.shareInboxDataStore.edit { prefs -> prefs.remove(itemsKey) }
    }

    private fun parseItems(json: String): List<InboxItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                InboxItem(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    name = o.optString("name").ifBlank { "ملف" },
                    extension = o.optString("extension"),
                    mimeType = o.optString("mimeType").ifBlank { "*/*" },
                    size = o.optLong("size"),
                    filePath = o.optString("filePath").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null,
                    receivedAt = o.optLong("receivedAt")
                )
            }
        }.getOrElse {
            Log.w(TAG, "تعذّر قراءة قائمة الصندوق — البدء فارغاً", it)
            emptyList()
        }
    }

    private fun serializeItems(list: List<InboxItem>): String {
        val array = JSONArray()
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("extension", item.extension)
                    .put("mimeType", item.mimeType)
                    .put("size", item.size)
                    .put("filePath", item.filePath)
                    .put("receivedAt", item.receivedAt)
            )
        }
        return array.toString()
    }

    companion object {
        private const val TAG = "ShareInbox"
    }
}
