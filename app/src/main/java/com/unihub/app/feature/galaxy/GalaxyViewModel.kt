package com.unihub.app.feature.galaxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.repository.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GalaxyViewModel @Inject constructor(folderRepository: FolderRepository) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    internal val uiState: StateFlow<GalaxyUiState> = refresh
        .flatMapLatest { folderRepository.observeFoldersWithFileCount().toGalaxyUiStates() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalaxyUiState.Loading)

    fun retry() {
        refresh.update { it + 1 }
    }
}
