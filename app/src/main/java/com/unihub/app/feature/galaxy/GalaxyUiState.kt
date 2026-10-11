package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal sealed interface GalaxyUiState {
    data object Loading : GalaxyUiState
    data class Ready(val folders: List<FolderWithFileCount>) : GalaxyUiState
    data object Error : GalaxyUiState
}

/** Empty means a completed empty query, not a query that has not returned yet. */
internal fun Flow<List<FolderWithFileCount>>.toGalaxyUiStates(): Flow<GalaxyUiState> =
    map<List<FolderWithFileCount>, GalaxyUiState> { GalaxyUiState.Ready(it) }
        .onStart { emit(GalaxyUiState.Loading) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(GalaxyUiState.Error)
        }
