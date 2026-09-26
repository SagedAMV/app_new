package com.unihub.app.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.Formatters
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.core.validation.InputValidationException
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.repository.FileRepository
import com.unihub.app.data.repository.FolderRepository
import com.unihub.app.feature.share.InboxItem
import com.unihub.app.feature.share.ShareInbox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

/**
 * ViewModel شاشة الملفات داخل مجلد معيّن.
 * التحسين عن المرجع: حالة الشاشة مشتقة عبر [combine] من تدفقات القاعدة مباشرة
 * (البحث والمفضلة يصفّيان لحظياً بلا إعادة استعلام)، وعمليات الاستيراد والنسخ
 * مفوضة لمستودعات/تخزين معزولة.
 *
 * جديد هذه الجولة: وضع التحديد المتعدد (نقر مطول) مع حذف/مشاركة جماعية،
 * ورسائل خطأ استيراد مفصلة حسب السبب الحقيقي.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderRepository: FolderRepository,
    private val fileRepository: FileRepository,
    private val shareInbox: ShareInbox
) : ViewModel() {

    private val folderId: Long? = savedStateHandle.get<Long?>("folderId")

    val messenger = UiMessenger()

    // ─── صندوق المشاركة (ملفات واردة من قائمة مشاركة النظام) ─────────────

    /** الملفات المشتركة التي تنتظر اختيار مجلدها — تظهر في كل شاشات الملفات */
    val inboxItems: StateFlow<List<InboxItem>> = shareInbox.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** معرّفات الملفات الموضوعة للتو — لإبرازها بانميشن تلاشي ثم يبهت التمييز */
    private val _recentlyPlaced = MutableStateFlow<Set<Long>>(emptySet())
    val recentlyPlaced: StateFlow<Set<Long>> = _recentlyPlaced.asStateFlow()

    /**
     * نسخ كل ملفات الصندوق إلى المجلد المفتوح حالياً: إدراج منطقي فقط —
     * النسخ الفعلية موجودة سلفاً في المكتبة المسطحة (نفس مبدأ النقل).
     */
    fun placeSharedFilesHere() {
        viewModelScope.launch {
            val placedIds = runCatching { shareInbox.placeAll(folderId) }
                .getOrElse {
                    messenger.notifyError("تعذّر وضع الملفات المشتركة")
                    return@launch
                }
            if (placedIds.isEmpty()) return@launch
            _recentlyPlaced.value = placedIds.toSet()
            messenger.notify(
                "وُضع " + Formatters.fileCountLabel(placedIds.size) +
                    if (folderId == null) " في المستوى الرئيسي" else " في هذا المجلد"
            )
            launch {
                delay(2_500)
                _recentlyPlaced.value = emptySet()
            }
        }
    }

    /** التخلي عن ملف مشترك واحد وحذف نسخته الفعلية */
    fun removeInboxItem(item: InboxItem) {
        viewModelScope.launch {
            runCatching { shareInbox.remove(item) }
                .onSuccess { messenger.notify("أُهمل الملف المشترك") }
                .onFailure { messenger.notifyError("تعذّر حذف الملف المشترك") }
        }
    }

    /** عداد يُحدَّث بعد أي تغيير على المجلد الحالي كي يُعاد جلب بياناته */
    private val folderVersion = MutableStateFlow(0)

    /**
     * بيانات المجلد الحالي (للعنوان) — الجذر يعيد null. مرتبط بـ [folderVersion]
     * حتى يظهر إعادة التسمية/الحذف في الترويسة فوراً بدل بقاء الاسم القديم.
     */
    val currentFolder: StateFlow<FolderEntity?> = folderVersion
        .flatMapLatest { flow { emit(folderId?.let { folderRepository.getById(it) }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()

    private val favoritesOnly = MutableStateFlow(false)
    val favoritesOnlyState: StateFlow<Boolean> = favoritesOnly.asStateFlow()

    private val importing = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = importing.asStateFlow()

    /** معرّفات الملفات المحددة (وضع النقر المطول) — فارغة = الوضع العادي */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    val folders: StateFlow<List<FolderEntity>> =
        combine(folderRepository.observeChildren(folderId), query) { folders, q ->
            // القص عند الاستخدام لا أثناء الكتابة (انظر setSearchQuery) حتى
            // تبقى مسافة لوحة المفاتيح قابلة للكتابة في البحث متعدد الكلمات
            val trimmed = q.trim()
            if (trimmed.isBlank()) folders
            else folders.filter { it.name.contains(trimmed, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val files: StateFlow<List<FileEntity>> =
        combine(
            fileRepository.observeInFolder(folderId),
            favoritesOnly,
            query
        ) { files, favOnly, q ->
            val trimmed = q.trim()
            files.filter { file ->
                (!favOnly || file.isFavorite) &&
                    (trimmed.isBlank() || file.name.contains(trimmed, ignoreCase = true))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** تخزين نص البحث كما كُتب — القص يحدث عند الاستخدام داخل المرشحات فقط */
    fun setSearchQuery(value: String) {
        query.value = value
    }

    fun toggleFavoritesFilter() {
        favoritesOnly.value = !favoritesOnly.value
    }

    // ─── وضع التحديد المتعدد ────────────────────────────────────────────

    /** نقر مطول: يبدأ التحديد أو يبدّل حالة ملف */
    fun toggleSelection(fileId: Long) {
        _selection.value = _selection.value.let { current ->
            if (fileId in current) current - fileId else current + fileId
        }
    }

    fun selectAll(fileIds: List<Long>) {
        _selection.value = fileIds.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** حذف جماعي مع تقرير نجاح/فشل دقيق — يُخرج الشاشة من وضع التحديد بعده */
    fun deleteFiles(files: List<FileEntity>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            files.forEach { file ->
                runCatching { fileRepository.delete(file) }
                    .onSuccess { ok++ }
                    .onFailure { failed++ }
            }
            _selection.value = emptySet()
            when {
                failed == 0 -> messenger.notify("حُذف " + Formatters.fileCountLabel(ok))
                ok > 0 -> messenger.notifyError("حُذف $ok وبقي $failed تعذّر حذفه")
                else -> messenger.notifyError("تعذّر حذف الملفات")
            }
        }
    }

    /**
     * نقل الملفات المحددة إلى مجلد آخر (أو إلى الجذر). التخزين الفيزيائي مسطح
     * فالنقل تحديث منطقي آمن — لا حركة ملفات على القرص. المجلد الحالي يُستبعد
     * من الوجهات في الواجهة، وهذه حماية دفاعية إضافية إن وصل نفس المجلد.
     */
    fun moveFiles(files: List<FileEntity>, targetFolderId: Long?) {
        if (files.isEmpty()) return
        if (targetFolderId == folderId) {
            messenger.notify("الملفات موجودة في هذا المجلد بالفعل")
            return
        }
        viewModelScope.launch {
            runCatching { fileRepository.moveToFolder(files.map { it.id }, targetFolderId) }
                .onSuccess {
                    messenger.notify(
                        "تم نقل " + Formatters.fileCountLabel(files.size) +
                            if (targetFolderId == null) " إلى المستوى الرئيسي" else ""
                    )
                }
                .onFailure { messenger.notifyError("تعذّر نقل الملفات") }
            _selection.value = emptySet()
        }
    }

    /** تفضيل/إلغاء تفضيل جماعي: إن كان أيٌّ منها غير مفضل فالتفضيل للجميع */
    fun setFavoriteFor(files: List<FileEntity>) {
        if (files.isEmpty()) return
        val target = files.any { !it.isFavorite }
        viewModelScope.launch {
            var failed = 0
            files.forEach { file ->
                runCatching { fileRepository.setFavorite(file.id, target) }
                    .onFailure { failed++ }
            }
            _selection.value = emptySet()
            if (failed == 0) {
                messenger.notify(if (target) "أُضيفت للمفضلة" else "أُزيلت من المفضلة")
            } else {
                messenger.notifyError("تعذّر تحديث المفضلة لبعض الملفات")
            }
        }
    }

    /**
     * شفاء ذاتي: سجل يشير لملف فيزيائي مفقود — نحذف السجل اليتيم
     * حتى لا تتراكم أشباح في القائمة (يُستدعى عندما يفشل الفتح بسبب الفقدان).
     */
    fun healMissingRecord(file: FileEntity) {
        viewModelScope.launch {
            runCatching { fileRepository.deleteRecord(file) }
                .onSuccess { messenger.notify("حُذف سجل الملف المفقود من القائمة") }
                .onFailure { messenger.notifyError("تعذّر تنظيف سجل الملف المفقود") }
            _selection.value = _selection.value - file.id
        }
    }

    // ─── المجلدات ───────────────────────────────────────────────────────

    fun createFolder(name: String, description: String, color: String) {
        viewModelScope.launch {
            val validName = InputValidator.validateName(name)
                .onFailure { messenger.notifyError(it.message ?: "اسم غير صالح") }
                .getOrNull() ?: return@launch
            runCatching {
                folderRepository.create(
                    FolderEntity(
                        name = validName,
                        description = InputValidator.sanitizeText(description),
                        color = color,
                        parentId = folderId
                    )
                )
            }.onSuccess { messenger.notify("تم إنشاء المجلد") }
                .onFailure { messenger.notifyError("فشل إنشاء المجلد — ربما الاسم مكرر أو القاعدة مشغولة") }
        }
    }

    fun renameFolder(folder: FolderEntity, newName: String) {
        viewModelScope.launch {
            val validName = InputValidator.validateName(newName)
                .onFailure { messenger.notifyError(it.message ?: "اسم غير صالح") }
                .getOrNull() ?: return@launch
            if (validName == folder.name) {
                messenger.notify("الاسم لم يتغير")
                return@launch
            }
            runCatching { folderRepository.update(folder.copy(name = validName)) }
                .onSuccess {
                    messenger.notify("تم إعادة التسمية")
                    if (folder.id == folderId) folderVersion.value++
                }
                .onFailure { messenger.notifyError("فشلت إعادة التسمية") }
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            runCatching { folderRepository.deleteDeep(folder) }
                .onSuccess { messenger.notify("حُذف المجلد ومحتوياته") }
                .onFailure { messenger.notifyError("فشل حذف المجلد") }
        }
    }

    /** لقطة كل المجلدات لمنتقي «نقل إلى مجلد» — تُجلب عند فتح المنتقي فقط */
    suspend fun allFoldersOnce(): List<FolderEntity> = folderRepository.allOnce()

    /** نقل مجلد إلى أب جديد مع رسائل دقيقة لكل سبب رفض */
    fun moveFolder(folder: FolderEntity, newParentId: Long?) {
        viewModelScope.launch {
            val result = runCatching { folderRepository.move(folder, newParentId) }
                .getOrElse {
                    messenger.notifyError("تعذّر نقل المجلد")
                    return@launch
                }
            when (result) {
                FolderRepository.MoveResult.MOVED -> messenger.notify(
                    if (newParentId == null) "نُقل المجلد إلى المستوى الرئيسي"
                    else "تم نقل المجلد"
                )
                FolderRepository.MoveResult.SAME_PLACE ->
                    messenger.notify("المجلد موجود هنا بالفعل")
                FolderRepository.MoveResult.CYCLE ->
                    messenger.notifyError("لا يمكن نقل المجلد داخل أحد مجلداته الفرعية")
                FolderRepository.MoveResult.MISSING_PARENT ->
                    messenger.notifyError("المجلد الهدف لم يعد موجوداً")
            }
            if (result == FolderRepository.MoveResult.MOVED && folder.id == folderId) {
                folderVersion.value++
            }
        }
    }

    // ─── الملفات ────────────────────────────────────────────────────────

    /** استيراد ملفات من منتقي النظام داخل المجلد الحالي — برسائل فشل مفصلة */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            var ok = 0
            var failedMsg: String? = null
            uris.forEach { uri ->
                runCatching { fileRepository.import(uri, "*/*", folderId) }
                    .onSuccess { ok++ }
                    .onFailure { e ->
                        failedMsg = when (e) {
                            is InputValidationException -> e.error.message
                            is SecurityException -> "انتهى إذن الوصول لأحد الملفات — اختره مجدداً"
                            is FileNotFoundException -> "أحد الملفات لم يعد متاحاً على الجهاز"
                            is IOException -> "تعذّرت قراءة أحد الملفات من الجهاز"
                            else -> "فشل استيراد أحد الملفات"
                        }
                    }
            }
            importing.value = false
            when {
                ok > 0 && failedMsg == null -> messenger.notify("تم استيراد $ok ملف بنجاح")
                ok > 0 -> messenger.notify("تم استيراد $ok ملف — $failedMsg")
                else -> messenger.notifyError(failedMsg ?: "فشل الاستيراد")
            }
        }
    }

    // ─── المرفقات الجديدة (كاميرا / تسجيل صوتي) ─────────────────────────

    /**
     * حفظ دفعات الصور الملتقطة بالكاميرا داخل المجلد الحالي.
     * كل عنصر زوج (الملف المؤقت في الكاش، الاسم الاختياري الذي أدخله المستخدم).
     * تقرير نجاح/فشل دقيق بنفس نمط الاستيراد من المنتقي.
     */
    fun saveCapturedImages(items: List<com.unihub.app.feature.files.capture.CapturedImageInput>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            importing.value = true
            var ok = 0
            var failed = 0
            items.forEach { item ->
                runCatching { fileRepository.saveCapturedImage(item.file, item.name, folderId) }
                    .onSuccess { ok++ }
                    .onFailure {
                        failed++
                        runCatching { item.file.delete() } // لا نترك ملفات مؤقتة يتيمة
                    }
            }
            importing.value = false
            when {
                ok > 0 && failed == 0 -> messenger.notify(
                    if (ok == 1) "أُضيفت الصورة إلى الملفات" else "أُضيفت $ok صور إلى الملفات"
                )
                ok > 0 -> messenger.notifyError("أُضيفت $ok صور وتعذّر حفظ $failed")
                else -> messenger.notifyError("تعذّر حفظ الصور الملتقطة")
            }
        }
    }

    /** حفظ تسجيل صوتي واحد داخل المجلد الحالي — الملف مؤقت في الكاش */
    fun saveAudioRecording(file: java.io.File, name: String?) {
        viewModelScope.launch {
            importing.value = true
            runCatching { fileRepository.saveAudioRecording(file, name, folderId) }
                .onSuccess { messenger.notify("تم حفظ التسجيل الصوتي") }
                .onFailure {
                    messenger.notifyError("تعذّر حفظ التسجيل الصوتي")
                    runCatching { file.delete() }
                }
            importing.value = false
        }
    }

    fun renameFile(file: FileEntity, newName: String) {
        viewModelScope.launch {
            val validName = InputValidator.validateName(newName)
                .onFailure { messenger.notifyError(it.message ?: "اسم غير صالح") }
                .getOrNull() ?: return@launch
            if (validName == file.name) {
                messenger.notify("الاسم لم يتغير")
                return@launch
            }
            runCatching { fileRepository.rename(file, validName) }
                .onSuccess { messenger.notify("تم إعادة التسمية") }
                .onFailure { messenger.notifyError("فشلت إعادة التسمية") }
        }
    }

    fun toggleFavorite(file: FileEntity) {
        viewModelScope.launch {
            runCatching { fileRepository.setFavorite(file.id, !file.isFavorite) }
                .onFailure { messenger.notifyError("تعذّر تحديث المفضلة") }
        }
    }
}
