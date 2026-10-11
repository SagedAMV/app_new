package com.unihub.app.feature.galaxy

import com.unihub.app.data.local.model.FolderWithFileCount
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyUiStateTest {
    @Test
    fun firstStateIsLoadingEvenWhenFoldersAlreadyExist() = runBlocking {
        val folders = listOf(folder())
        val states = flowOf(folders).toGalaxyUiStates().toList()
        assertEquals(listOf(GalaxyUiState.Loading, GalaxyUiState.Ready(folders)), states)
    }

    @Test
    fun emptyStateOnlyAppearsAfterTheQueryActuallyReturns() = runBlocking {
        val states = flowOf(emptyList<FolderWithFileCount>()).toGalaxyUiStates().toList()
        assertEquals(GalaxyUiState.Loading, states.first())
        assertEquals(GalaxyUiState.Ready(emptyList()), states.last())
    }

    @Test
    fun databaseFailureBecomesAnExplicitErrorNotAnEmptyGalaxy() = runBlocking {
        val states = flow<List<FolderWithFileCount>> { throw IOException("test failure") }.toGalaxyUiStates().toList()
        assertEquals(listOf(GalaxyUiState.Loading, GalaxyUiState.Error), states)
    }

    @Test
    fun laterDeletionIsARealEmptyResultNotAnotherLoadingState() = runBlocking {
        val states = flowOf(listOf(folder()), emptyList()).toGalaxyUiStates().toList()
        assertEquals(3, states.size)
        assertTrue(states[1] is GalaxyUiState.Ready)
        assertEquals(GalaxyUiState.Ready(emptyList()), states[2])
    }

    @Test
    fun cancellationIsNotSwallowedAsAUiError() = runBlocking {
        try {
            flow<List<FolderWithFileCount>> { throw CancellationException("cancelled") }.toGalaxyUiStates().toList()
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected lifecycle/navigation cancellation, not a database error.
        }
    }

    private fun folder() = FolderWithFileCount(1L, "مادة", "#4E7D6E", 2)
}
