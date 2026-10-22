package com.xayah.feature.main.settings.blacklist

import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.model.OpType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class IndexUiState(
    val appIds: Set<Long>,
    val fileIds: Set<Long>,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data class SelectApp(val id: Long) : IndexUiIntent()
    data class SelectFile(val id: Long) : IndexUiIntent()
    data object SelectAll : IndexUiIntent()
    data object RemoveSelected : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    private val packageRepo: PackageRepository,
    private val mediaRepo: MediaRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        appIds = setOf(),
        fileIds = setOf()
    )
) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.SelectApp -> {
                updateState {
                    it.copy(appIds = if (intent.id in it.appIds) it.appIds - intent.id else it.appIds + intent.id)
                }
            }

            is IndexUiIntent.SelectFile -> {
                updateState {
                    it.copy(fileIds = if (intent.id in it.fileIds) it.fileIds - intent.id else it.fileIds + intent.id)
                }
            }

            is IndexUiIntent.SelectAll -> {
                val appIds = packagesState.value.mapTo(mutableSetOf()) { it.id }
                val fileIds = mediumState.value.mapTo(mutableSetOf()) { it.id }
                updateState {
                    val allSelected = it.appIds.containsAll(appIds) && it.fileIds.containsAll(fileIds)
                    it.copy(
                        appIds = if (allSelected) emptySet() else appIds,
                        fileIds = if (allSelected) emptySet() else fileIds,
                    )
                }
            }

            is IndexUiIntent.RemoveSelected -> {
                state.appIds.forEach { id ->
                    packageRepo.setBlocked(id, false)
                    updateState { it.copy(appIds = it.appIds - id) }
                }
                state.fileIds.forEach { id ->
                    mediaRepo.setBlocked(id, false)
                    updateState { it.copy(fileIds = it.fileIds - id) }
                }
            }
        }
    }

    private val _packages: Flow<List<PackageEntity>> = packageRepo.queryPackagesFlow(opType = OpType.BACKUP, blocked = true).flowOnIO()
    val packagesState: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())

    private val _medium: Flow<List<MediaEntity>> = mediaRepo.queryFlow(opType = OpType.BACKUP, blocked = true).flowOnIO()
    val mediumState: StateFlow<List<MediaEntity>> = _medium.stateInScope(listOf())
}
