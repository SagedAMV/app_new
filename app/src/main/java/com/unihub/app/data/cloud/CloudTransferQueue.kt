package com.unihub.app.data.cloud

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * حالة دفعة نقل واحدة في طابور الخلفية.
 * لا توجد حالة «RUNNING» محفوظة عمدًا: الـworker قد يُقتل في أي لحظة (نظام/بطارية)،
 * فأي حالة «تُنفَّذ الآن» ستبقى معلّقة للأبد. ما لم يكتمل يعود إلى [PENDING] تلقائيًا.
 */
enum class CloudTransferStatus { PENDING, DONE, FAILED }

/**
 * دفعة نقل = فعل واحد من أفعال المستخدم (رفع ما حدّده، أو تنزيل مجلد/ملفات مختارة).
 * تُجمَّد فيها خطة الرفع والوجهة كما اختارهما المستخدم، فينفّذها الـworker كما هي:
 * لا قرار واجهة داخل طبقة البيانات، ولا إعادة حساب قد تعطي نتيجة مختلفة لاحقًا.
 */
data class CloudTransferBatch(
    val id: String,
    val kind: CloudTransferKind,
    val title: String,
    val fileIds: Set<Long> = emptySet(),
    val folderIds: Set<Long> = emptySet(),
    val totalBytes: Long = 0L,
    val remoteKeys: List<String> = emptyList(),
    val remoteFolderKeys: Set<String> = emptySet(),
    val destination: CloudDownloadDestination = CloudDownloadDestination(),
    val status: CloudTransferStatus = CloudTransferStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String = "",
    val createdAt: Long = 0L
) {
    /** بصمة الدفعة: تُستعمل لمنع تكرار نفس الفعل وهو ما زال في الانتظار */
    val dedupeKey: String
        get() = listOf(
            kind.name,
            fileIds.sorted().joinToString(","),
            folderIds.sorted().joinToString(","),
            remoteKeys.sorted().joinToString(","),
            remoteFolderKeys.sorted().joinToString(","),
            destination.location.name,
            destination.localFolderId?.toString() ?: "",
            destination.rootFolderName ?: ""
        ).joinToString("|")

    /** عدد العناصر التي ستنُقل/تُرفَع في هذه الدفعة — للواجهة والإشعار */
    val itemCount: Int get() = if (kind == CloudTransferKind.UPLOAD) fileIds.size + folderIds.size else remoteKeys.size + remoteFolderKeys.size
}

/** ما يجب أن تفعله نافذة النقل بعد نهاية جولتها */
enum class CloudTransferRearm { NONE, WAIT_FOR_NETWORK, RETRY_SOON }

/** لقطة الطابور كما تُخزَّن وكما تقرأها الواجهة */
data class CloudTransferQueueSnapshot(
    val batches: List<CloudTransferBatch> = emptyList(),
    val paused: Boolean = false
) {
    val pending: List<CloudTransferBatch> get() = batches.filter { it.status == CloudTransferStatus.PENDING }
    val failed: List<CloudTransferBatch> get() = batches.filter { it.status == CloudTransferStatus.FAILED }
    val pendingItems: Int get() = pending.sumOf { it.itemCount }
    val isEmpty: Boolean get() = batches.isEmpty()

    /** سطر واحد مختصر للواجهة والإشعار — لا يُترك المستخدم يخمّن ماذا في الانتظار */
    val summary: String
        get() {
            val parts = buildList {
                if (paused) add("موقوف مؤقتًا")
                if (pending.isNotEmpty()) add("بانتظار ${pending.size} عملية (${pendingItems} عنصر)")
                if (failed.isNotEmpty()) add("تعذّر تنفيذ ${failed.size} — يمكن إعادة المحاولة")
                if (isEmpty) add("لا نقل معلّق")
            }
            return parts.joinToString(" • ")
        }
}

/**
 * منطق الطابور وحده — نقي وبلا Android وبلا شبكة، فيُختبر محليًا على JVM.
 * التخزين والتنفيذ يعيشان خارجه عمدًا (§28 طبيعة تطبيق.md: Data → State → UI).
 */
object CloudTransferQueueLogic {

    /** بعد هذا العدد من المحاولات تبقى الدفعة «مستثناة» حتى يعيدها المستخدم يدويًا */
    const val MAX_ATTEMPTS = 4

    /** الحد الأقصى لما يبقى محفوظًا من المنتهية حتى لا ينمو الملف بلا نهاية */
    private const val KEEP_FINISHED = 12

    /** دفعة بلا شيء تُنقله مرفوضة: كانت ستظهر في الانتظار ثم تفشل بلا سبب مفهوم */
    private fun isUseful(batch: CloudTransferBatch): Boolean = when (batch.kind) {
        CloudTransferKind.UPLOAD -> batch.fileIds.isNotEmpty() || batch.folderIds.isNotEmpty()
        CloudTransferKind.DOWNLOAD -> batch.remoteKeys.isNotEmpty() || batch.remoteFolderKeys.isNotEmpty()
        CloudTransferKind.NONE -> false
    }

    fun enqueue(snapshot: CloudTransferQueueSnapshot, batch: CloudTransferBatch, now: Long): CloudTransferQueueSnapshot {
        if (!isUseful(batch)) return snapshot
        val alreadyQueued = snapshot.batches.any {
            it.status == CloudTransferStatus.PENDING && it.dedupeKey == batch.dedupeKey
        }
        if (alreadyQueued) return snapshot
        val ready = batch.copy(createdAt = if (batch.createdAt > 0L) batch.createdAt else now)
        return prune(snapshot.copy(batches = snapshot.batches + ready))
    }

    /**
     * نص إشعار النتيجة — null أي لا شيء يستحق مقابلًا بصريًا.
     *
     * يُنبَّه المستخدم عند تعذّر شيء أو توقفه فقط: هذا ما يجعل المتابعة ممكنة وهو خارج
     * التطبيق، والصمت عند النجاح الكامل حتى لا يتحوّل كل رفع إلى تنبيه مزعج.
     */
    fun outcomeNotificationText(snapshot: CloudTransferQueueSnapshot, completedItems: Int): String? {
        // لا «اكتمل» من الطابور نفسه: المنتهية تبقى محفوظة للمقارنة، فعدد هذا التشغيل
        // هو ما يُعرض وإلا قرأ المستخدم أرقامًا من دفعات قديمة.
        val failedItems = snapshot.failed.sumOf { it.itemCount }
        val waiting = snapshot.pendingItems
        return when {
            failedItems > 0 && completedItems > 0 ->
                "اكتمل $completedItems عنصرًا • تعذّر $failedItems — افتح التطبيق لمعرفة السبب وإعادة المحاولة"
            failedItems > 0 ->
                "تعذّر $failedItems في طابور النقل — افتح التطبيق لمعرفة السبب وإعادة المحاولة"
            snapshot.paused && waiting > 0 ->
                "النقل متوقف مؤقتًا • $waiting في الانتظار — افتح التطبيق للاستئناف"
            else -> null
        }
    }

    /** العنصر التالي بحسب ترتيب الطلب (FIFO)؛ الإيقاف المؤقت يمنع كل تنفيذ جديد */
    fun nextPending(snapshot: CloudTransferQueueSnapshot): CloudTransferBatch? =
        if (snapshot.paused) null else snapshot.pending.minByOrNull { it.createdAt }

    /** [note] ملاحظة تُعرض للمستخدم مع اكتمال الدفعة (مثل: ملف اختفى من الجهاز أثناء الانتظار) */
    fun markDone(snapshot: CloudTransferQueueSnapshot, batchId: String, note: String = ""): CloudTransferQueueSnapshot =
        replace(snapshot, batchId) { it.copy(status = CloudTransferStatus.DONE, lastError = note) }
            .let { prune(it) }

    /**
     * يُسجَّل الفشل مع رسالة المستخدم. والأعطال التي سببها انشغال القفل أو انقطاع
     * الشبكة ([retryLater]) تُبقي الدفعة [CloudTransferStatus.PENDING] **ولا تستهلك محاولة**،
     * وإلا استُنزف [MAX_ATTEMPTS] أثناء انقطاع طويل فاحتاج تدخلاً يدويًا بلا داعٍ.
     */
    fun markFailed(snapshot: CloudTransferQueueSnapshot, batchId: String, error: String, retryLater: Boolean): CloudTransferQueueSnapshot =
        replace(snapshot, batchId) { batch ->
            if (retryLater) batch.copy(status = CloudTransferStatus.PENDING, lastError = error)
            else {
                val attempts = batch.attempts + 1
                batch.copy(
                    status = if (attempts >= MAX_ATTEMPTS) CloudTransferStatus.FAILED else CloudTransferStatus.PENDING,
                    attempts = attempts,
                    lastError = error
                )
            }
        }

    /** «إعادة المحاولة» بعد الاستثناءات: تعيد كل الفاشلة إلى الانتظار بصفر محاولات */
    fun retryFailed(snapshot: CloudTransferQueueSnapshot): CloudTransferQueueSnapshot =
        snapshot.copy(batches = snapshot.batches.map { batch ->
            if (batch.status == CloudTransferStatus.FAILED) batch.copy(status = CloudTransferStatus.PENDING, attempts = 0)
            else batch
        }).let { prune(it) }

    fun setPaused(snapshot: CloudTransferQueueSnapshot, paused: Boolean): CloudTransferQueueSnapshot =
        snapshot.copy(paused = paused)

    /** إلغاء = تُزال الدفعات التي لم تبدأ فقط؛ ما اكتمل يبقى منجزًا ولا تراجع عنه، والفاشل يبقى ضمن الاستثناءات */
    fun cancelPending(snapshot: CloudTransferQueueSnapshot): CloudTransferQueueSnapshot =
        prune(snapshot.copy(batches = snapshot.batches.filterNot { it.status == CloudTransferStatus.PENDING }))

    fun removeBatch(snapshot: CloudTransferQueueSnapshot, batchId: String): CloudTransferQueueSnapshot =
        prune(snapshot.copy(batches = snapshot.batches.filterNot { it.id == batchId }))

    /**
     * ما يجب على نافذة العمل أن تفعله بعد نهاية جولتها — قرار كدالة نقية حتى يُختبر محليًا.
     * الاعتماد على [androidx.work.ListenableWorker.Result.retry] وحده كان عطلًا حقيقيًا:
     * العامل الذي يُوقَف لسقوط شرط الشبكة تُهمَل نتيجته، ولا أحد يعيد الجدولة عند عودة
     * الاتصال، فتبقى الدفعات «قيد الانتظار» بلا نهاية. الصواب: تسليح طلب جديد مقيّد بالشبكة
     * فينفّذه النظام لحظة عودتها — لا انتظار تأخير backoff الأُسّي الذي قد يمتد ساعات.
     */
    fun rearmAction(
        snapshot: CloudTransferQueueSnapshot,
        networkAvailable: Boolean,
        workerStopped: Boolean,
        busyEncountered: Boolean
    ): CloudTransferRearm {
        if (snapshot.pending.isEmpty() || snapshot.paused) return CloudTransferRearm.NONE
        if (!networkAvailable || workerStopped) return CloudTransferRearm.WAIT_FOR_NETWORK
        // انشغال قفل المدير: نحن على شبكة سليمة، وجولة قريبة بعد backoff هي الأنسب
        return CloudTransferRearm.RETRY_SOON
    }

    /** يبقي كل المنتظرة مرتّبة مع آخر [KEEP_FINISHED] منتهية فقط — الطابور يبقى صغيرًا ومقروءًا */
    fun prune(snapshot: CloudTransferQueueSnapshot): CloudTransferQueueSnapshot {
        val active = snapshot.batches.filter { it.status == CloudTransferStatus.PENDING }.sortedBy { it.createdAt }
        val finished = snapshot.batches.filter { it.status != CloudTransferStatus.PENDING }
            .sortedByDescending { it.createdAt }
            .take(KEEP_FINISHED)
            .sortedBy { it.createdAt }
        return snapshot.copy(batches = active + finished)
    }
}

private fun replace(
    snapshot: CloudTransferQueueSnapshot,
    batchId: String,
    transform: (CloudTransferBatch) -> CloudTransferBatch
): CloudTransferQueueSnapshot =
    snapshot.copy(batches = snapshot.batches.map { if (it.id == batchId) transform(it) else it })

private val QUEUE_KEY = stringPreferencesKey("queue_json")

/** فكّ ترميز الطابور — دالة مستقلة حتى تُختبر وحدها (فارغ/تالف/غير صالح/حقول ناقصة) */
internal fun decodeQueueJson(text: String?): CloudTransferQueueSnapshot {
    if (text.isNullOrBlank()) return CloudTransferQueueSnapshot()
    val root = try { JSONObject(text) } catch (_: Exception) { return CloudTransferQueueSnapshot() }
    val array = root.optJSONArray("batches") ?: JSONArray()
    val batches = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::batchFromJson) }
    return CloudTransferQueueSnapshot(batches = batches, paused = root.optBoolean("paused"))
}

internal fun encodeQueueJson(snapshot: CloudTransferQueueSnapshot): String = JSONObject().apply {
    put("paused", snapshot.paused)
    put("batches", JSONArray().also { array -> snapshot.batches.forEach { array.put(batchToJson(it)) } })
}.toString()

private fun batchToJson(batch: CloudTransferBatch): JSONObject = JSONObject().apply {
    put("id", batch.id)
    put("kind", batch.kind.name)
    put("title", batch.title)
    put("fileIds", JSONArray(batch.fileIds.toList()))
    put("folderIds", JSONArray(batch.folderIds.toList()))
    put("totalBytes", batch.totalBytes)
    put("remoteKeys", JSONArray(batch.remoteKeys))
    put("remoteFolderKeys", JSONArray(batch.remoteFolderKeys.toList()))
    put("location", batch.destination.location.name)
    put("localFolderId", batch.destination.localFolderId ?: JSONObject.NULL)
    put("localFolderCreatedAt", batch.destination.localFolderCreatedAt ?: JSONObject.NULL)
    put("rootFolderName", batch.destination.rootFolderName ?: JSONObject.NULL)
    put("status", batch.status.name)
    put("attempts", batch.attempts)
    put("lastError", batch.lastError)
    put("createdAt", batch.createdAt)
}

private fun batchFromJson(obj: JSONObject): CloudTransferBatch? {
    val id = obj.optString("id")
    if (id.isBlank()) return null
    val kind = CloudTransferKind.values().firstOrNull { it.name == obj.optString("kind") } ?: return null
    val destination = CloudDownloadDestination(
        location = CloudDownloadLocation.values().firstOrNull { it.name == obj.optString("location") }
            ?: CloudDownloadDefaults.location,
        localFolderId = if (obj.isNull("localFolderId")) null else obj.optLong("localFolderId"),
        localFolderCreatedAt = if (obj.isNull("localFolderCreatedAt")) null else obj.optLong("localFolderCreatedAt"),
        rootFolderName = if (obj.isNull("rootFolderName")) null else obj.optString("rootFolderName").takeIf { it.isNotBlank() }
    )
    return CloudTransferBatch(
        id = id,
        kind = kind,
        title = obj.optString("title"),
        fileIds = obj.optJSONArray("fileIds").longSet(),
        folderIds = obj.optJSONArray("folderIds").longSet(),
        totalBytes = obj.optLong("totalBytes"),
        remoteKeys = obj.optJSONArray("remoteKeys").stringList(),
        remoteFolderKeys = obj.optJSONArray("remoteFolderKeys").stringList().toSet(),
        destination = destination,
        status = CloudTransferStatus.values().firstOrNull { it.name == obj.optString("status") }
            ?: CloudTransferStatus.PENDING,
        attempts = obj.optInt("attempts"),
        lastError = obj.optString("lastError"),
        createdAt = obj.optLong("createdAt")
    )
}

private fun JSONArray?.longSet(): Set<Long> {
    if (this == null) return emptySet()
    return (0 until length()).mapNotNull { index ->
        val asLong = optLong(index, Long.MIN_VALUE)
        if (asLong != Long.MIN_VALUE) asLong else optString(index).toLongOrNull()
    }.toSet()
}

private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}

private val Context.cloudTransferDataStore by preferencesDataStore(
    name = "unihub_cloud_transfers",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * تخزين الطابور: نفس نمط [CloudCatalogStore] في المشروع (DataStore Preferences + JSON،
 * مع تعافٍ صامت من تلف الملف) — لا مكتبة جديدة ولا جدول Room، فالحالة معرّفات صغيرة
 * لا محتوى ملفات (§13 احترام المعمارية، §14 لا Dependency بلا حاجة موضّحة).
 */
@Singleton
class CloudTransferQueueStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** تدفّق حيّ للواجهة: تُقرأ منه بطاقة «بانتظار n» فلا تُخمَّن الحالة من مصدرين */
    val state: Flow<CloudTransferQueueSnapshot> =
        context.cloudTransferDataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { prefs -> decodeQueueJson(prefs[QUEUE_KEY]) }

    suspend fun snapshot(): CloudTransferQueueSnapshot = decodeQueueJson(readJson())

    suspend fun update(transform: (CloudTransferQueueSnapshot) -> CloudTransferQueueSnapshot) {
        context.cloudTransferDataStore.edit { prefs -> prefs[QUEUE_KEY] = encodeQueueJson(transform(decodeQueueJson(prefs[QUEUE_KEY]))) }
    }

    suspend fun enqueue(batch: CloudTransferBatch, now: Long = System.currentTimeMillis()) {
        update { CloudTransferQueueLogic.enqueue(it, batch, now) }
    }

    private suspend fun readJson(): String? =
        context.cloudTransferDataStore.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[QUEUE_KEY]
}
