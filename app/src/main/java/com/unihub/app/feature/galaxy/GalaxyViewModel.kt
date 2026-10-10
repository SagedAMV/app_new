package com.unihub.app.feature.galaxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.repository.FolderRepository
import com.unihub.app.data.local.model.FolderWithFileCount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GalaxyViewModel @Inject constructor(
    folderRepository: FolderRepository
) : ViewModel() {

    /** القائمة الكاملة مع parentId؛ تستخدمها المجرة لبناء خريطة الأبوة والأبناء. */
    val folders: StateFlow<List<FolderWithFileCount>> =
        folderRepository.observeFoldersWithFileCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
