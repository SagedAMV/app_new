package com.unihub.app.feature.galaxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unihub.app.data.repository.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// نموذج [GalaxyCluster] يعرَّف الآن في [GalaxyLayoutEngine] بجوار محرك
// التخطيط النقي الذي يستهلكه — نفس الحزمة، بلا أي تغيير على المستدعين.

@HiltViewModel
class GalaxyViewModel @Inject constructor(
    folderRepository: FolderRepository
) : ViewModel() {

    /**
     * عناقيد المجرّة: كل مجلد جذر مع أبنائه (التداخل مستوى واحد في نموذج البيانات).
     * إجراء أمان: إن وصل ابن في البث قبل أمه أو بدون أم، يُعرض عنقوداً مستقلاً
     * حتى لا يختفي أي مجلد من السماء.
     */
    val clusters: StateFlow<List<GalaxyCluster>> =
        folderRepository.observeFoldersWithFileCount()
            .map { folders ->
                val childrenByParent = folders
                    .filter { it.parentId != null }
                    .groupBy { it.parentId }
                val roots = folders.filter { it.parentId == null }
                val rootIds = roots.map { it.folderId }.toSet()
                val rootClusters = roots.map { root ->
                    GalaxyCluster(root, childrenByParent[root.folderId].orEmpty())
                }
                val orphanClusters = folders
                    .filter { folder ->
                        val pid = folder.parentId
                        pid != null && pid !in rootIds
                    }
                    .map { GalaxyCluster(it, emptyList()) }
                rootClusters + orphanClusters
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
