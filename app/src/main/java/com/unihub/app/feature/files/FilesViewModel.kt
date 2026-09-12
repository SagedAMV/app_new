package com.unihub.app.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.core.common.UiMessenger
import com.unihub.app.core.validation.InputValidator
import com.unihub.app.core.validation.InputValidationException
import com.unihub.app.data.local.entity.FileEntity
import com.unihub.app.data.local.entity.FolderEntity
import com.unihub.app.data.repository.FileRepository
import com.unihub.app.data.repository.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel شاشة الملفات داخل مجلد معيّن.
 * التحسين عن المرجع: حالة الشاشة مشتقة عبر [combine] من تدفقات القاعدة مباشرة
 * (البحث والمفضلة يصفّيان لحظياً بلا إعادة استعلام)، وعمليات الاستيراد والنسخ
 * مفوضة لمستودعات/تخزين معزولة.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderRepository: FolderRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val folderId: Long? = savedStateHandle.get<Long?>("folderId")

    val messenger = UiMessenger()

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

    val folders: StateFlow<List<FolderEntity>> =
        combine(folderRepository.observeChildren(folderId), query) { folders, q ->
            if (q.isBlank()) folders
            else folders.filter { it.name.contains(q, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val files: StateFlow<List<FileEntity>> =
        combine(
            fileRepository.observeInFolder(folderId),
            favoritesOnly,
            query
        ) { files, favOnly, q ->
            files.filter { file ->
                (!favOnly || file.isFavorite) &&
                    (q.isBlank() || file.name.contains(q, ignoreCase = true))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(value: String) {
        query.value = value.trim()
    }

    fun toggleFavoritesFilter() {
        favoritesOnly.value = !favoritesOnly.value
    }

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
                .onFailure { messenger.notifyError("فشل إنشاء المجلد") }
        }
    }

    fun renameFolder(folder: FolderEntity, newName: String) {
        viewModelScope.launch {
            val validName = InputValidator.validateName(newName)
                .onFailure { messenger.notifyError(it.message ?: "اسم غير صالح") }
                .getOrNull() ?: return@launch
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

    /** استيراد ملفات من منتقي النظام داخل المجلد الحالي */
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
                        failedMsg = (e as? InputValidationException)?.error?.message
                            ?: "فشل استيراد أحد الملفات"
                    }
            }
            importing.value = false
            when {
                ok > 0 && failedMsg == null -> messenger.notify("تم استيراد $ok ملف بنجاح")
                ok > 0 -> messenger.notify("تم استيراد $ok ملف — ${failedMsg}")
                else -> messenger.notifyError(failedMsg ?: "فشل الاستيراد")
            }
        }
    }

    fun renameFile(file: FileEntity, newName: String) {
        viewModelScope.launch {
            val validName = InputValidator.validateName(newName)
                .onFailure { messenger.notifyError(it.message ?: "اسم غير صالح") }
                .getOrNull() ?: return@launch
            runCatching { fileRepository.rename(file, validName) }
                .onSuccess { messenger.notify("تم إعادة التسمية") }
                .onFailure { messenger.notifyError("فشلت إعادة التسمية") }
        }
    }

    fun deleteFile(file: FileEntity) {
        viewModelScope.launch {
            runCatching { fileRepository.delete(file) }
                .onSuccess { messenger.notify("حُذف الملف") }
                .onFailure { messenger.notifyError("فشل حذف الملف") }
        }
    }

    fun toggleFavorite(file: FileEntity) {
        viewModelScope.launch {
            runCatching { fileRepository.setFavorite(file.id, !file.isFavorite) }
                .onFailure { messenger.notifyError("تعذّر تحديث المفضلة") }
        }
    }
}
