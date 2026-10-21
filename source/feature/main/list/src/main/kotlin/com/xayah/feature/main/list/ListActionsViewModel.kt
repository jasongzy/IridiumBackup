package com.xayah.feature.main.list

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.App
import com.xayah.core.model.AppKey
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.ColoredLabel
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.launchOnDefault
import com.xayah.core.work.WorkManagerInitializer
import com.xayah.feature.main.list.ListActionsUiState.Loading
import com.xayah.feature.main.list.ListActionsUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ListActionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
) : ViewModel() {
    private val target: Target = savedStateHandle.get<String>(MainRoutes.ARG_TARGET)
        ?.let { Target.valueOf(it.decodeURL().trim()) }
        ?: Target.Apps
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE)?.decodeURL()?.trim())
    private val cloudName: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
    private val backupDir: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE)?.decodeURL()?.trim() ?: ""

    val uiState: StateFlow<ListActionsUiState> = when (target) {
        Target.Apps -> combine(
            listDataRepo.getListData(),
            listDataRepo.getAppList(),
            labelsRepo.getColoredLabelsFlow(),
        ) { lData, aList, labels ->
            val listData = lData.castTo<ListData.Apps>()
            Success.Apps(
                opType = opType,
                selected = listData.selected,
                selectionMode = listData.selectionMode,
                isUpdating = listData.isUpdating,
                sortIndex = listData.sortIndex,
                sortType = listData.sortType,
                appList = aList,
                labels = labels,
            )
        }

        Target.Files -> combine(
            listDataRepo.getListData(),
            listDataRepo.getFileList()
        ) { lData, fList ->
            val listData = lData.castTo<ListData.Files>()
            Success.Files(
                opType = opType,
                selected = listData.selected,
                selectionMode = listData.selectionMode,
                isUpdating = listData.isUpdating,
                sortIndex = listData.sortIndex,
                sortType = listData.sortType,
                fileList = fList,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun refresh() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    when (opType) {
                        OpType.BACKUP -> {
                            WorkManagerInitializer.fastInitializeAndUpdateApps(context)
                            if ((uiState.value as Success.Apps).sortIndex == 2) {
                                WorkManagerInitializer.updateAppSizes(context)
                            }
                        }

                        OpType.RESTORE -> {
                            WorkManagerInitializer.loadAppBackups(context, cloudName, backupDir)
                        }
                    }
                }

                is Success.Files -> {
                    when (opType) {
                        OpType.BACKUP -> {
                            WorkManagerInitializer.fastInitializeAndUpdateFiles(context)
                        }

                        OpType.RESTORE -> {
                            WorkManagerInitializer.loadFileBackups(context, cloudName, backupDir)
                        }
                    }
                }

                else -> {}
            }
        }
    }

    fun showFilterSheet() {
        viewModelScope.launchOnDefault {
            listDataRepo.setShowFilterSheet(true)
        }
    }

    fun setSortByType() {
        viewModelScope.launchOnDefault {
            listDataRepo.setSortType { if (it == com.xayah.core.model.SortType.ASCENDING) com.xayah.core.model.SortType.DESCENDING else com.xayah.core.model.SortType.ASCENDING }
        }
    }

    fun setSortByIndex(index: Int) {
        viewModelScope.launchOnDefault {
            listDataRepo.setSortIndex { index }
        }
    }

    fun selectAll(visibleAppKeys: Collection<AppKey>) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    listDataRepo.selectApps(visibleAppKeys)
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.selectAll(state.fileList.map { it.id })
                }

                else -> {}
            }

        }
    }

    fun unselectAll(visibleAppKeys: Collection<AppKey>) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    listDataRepo.unselectApps(visibleAppKeys)
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.unselectAll(state.fileList.map { it.id })
                }

                else -> {}
            }

        }
    }

    fun reverseAll(visibleAppKeys: Collection<AppKey>) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    listDataRepo.reverseAppSelection(visibleAppKeys)
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    filesRepo.reverseAll(state.fileList.map { it.id })
                }

                else -> {}
            }

        }
    }

    fun selectRange(visibleAppKeys: List<AppKey>) {
        viewModelScope.launchOnDefault {
            listDataRepo.selectApps(keysInSelectedRange(visibleAppKeys, listDataRepo.getSelectedAppKeys().value))
        }
    }

    fun blockSelected() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    val ids = state.appList.filter { it.selected }.map { it.id }
                    if (ids.isNotEmpty()) {
                        appsRepo.blockSelected(ids)
                        showToast(R.string.items_added_to_blacklist)
                    }
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    val ids = state.fileList.filter { it.selected }.map { it.id }
                    if (ids.isNotEmpty()) {
                        filesRepo.blockSelected(ids)
                        showToast(R.string.items_added_to_blacklist)
                    }
                }

                else -> {}
            }

        }
    }

    fun showDataItemsSheet() {
        viewModelScope.launchOnDefault {
            listDataRepo.setShowDataItemsSheet(true)
        }
    }

    fun deleteSelected() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.Apps -> {
                    val state = uiState.value.castTo<Success.Apps>()
                    val ids = state.appList.filter { it.selected }.map { it.id }
                    if (ids.isNotEmpty()) {
                        appsRepo.deleteSelected(ids)
                        showToast(R.string.items_deleted)
                    }
                }

                is Success.Files -> {
                    val state = uiState.value.castTo<Success.Files>()
                    val ids = state.fileList.filter { it.selected }.map { it.id }
                    if (ids.isEmpty()) return@launchOnDefault
                    when (opType) {
                        OpType.BACKUP -> {
                            filesRepo.delete(ids)
                        }

                        OpType.RESTORE -> {
                            filesRepo.deleteSelected(ids)
                        }
                    }
                    showToast(R.string.items_deleted)
                }

                else -> {}
            }

        }
    }

    fun addFiles(pathList: List<String>) {
        viewModelScope.launchOnDefault {
            filesRepo.addFiles(pathList)
        }
    }

    fun createLabel(label: String) {
        viewModelScope.launchOnDefault {
            val normalized = label.trim()
            if (normalized.isNotEmpty()) labelsRepo.addLabel(normalized)
            if (normalized.isNotEmpty()) showToast(R.string.label_created)
        }
    }

    fun addLabelsToSelected(labels: Set<String>) {
        viewModelScope.launchOnDefault {
            val state = uiState.value as? Success.Apps ?: return@launchOnDefault
            labelsRepo.addLabelAppCrossRefs(labels.flatMap { state.selectedLabelRefs(it) })
            showToast(R.string.labels_updated)
        }
    }

    fun removeLabelsFromSelected(labels: Set<String>) {
        viewModelScope.launchOnDefault {
            val state = uiState.value as? Success.Apps ?: return@launchOnDefault
            labelsRepo.deleteLabelAppCrossRefs(labels.flatMap { state.selectedLabelRefs(it) })
            showToast(R.string.labels_updated)
        }
    }

    private fun Success.Apps.selectedLabelRefs(label: String): List<LabelAppCrossRefEntity> {
        val installedByKey = appList.associateBy(App::key)
        return listDataRepo.getSelectedAppKeys().value.map { key ->
            LabelAppCrossRefEntity(label, key.packageName, key.userId, installedByKey[key]?.preserveId ?: 0)
        }
    }

    private suspend fun showToast(message: Int) = withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

internal fun keysInSelectedRange(orderedKeys: List<AppKey>, selectedKeys: Set<AppKey>): List<AppKey> {
    val first = orderedKeys.indexOfFirst(selectedKeys::contains)
    val last = orderedKeys.indexOfLast(selectedKeys::contains)
    return if (first >= 0 && last > first) orderedKeys.subList(first, last + 1) else emptyList()
}

sealed interface ListActionsUiState {
    data object Loading : ListActionsUiState
    sealed class Success(
        open val opType: OpType,
        open val selected: Long,
        open val selectionMode: Boolean,
        open val isUpdating: Boolean,
        open val sortIndex: Int,
        open val sortType: com.xayah.core.model.SortType,
    ) : ListActionsUiState {
        data class Apps(
            override val opType: OpType,
            override val selected: Long,
            override val selectionMode: Boolean,
            override val isUpdating: Boolean,
            override val sortIndex: Int,
            override val sortType: com.xayah.core.model.SortType,
            val appList: List<App>,
            val labels: List<ColoredLabel>,
        ) : Success(opType, selected, selectionMode, isUpdating, sortIndex, sortType)

        data class Files(
            override val opType: OpType,
            override val selected: Long,
            override val selectionMode: Boolean,
            override val isUpdating: Boolean,
            override val sortIndex: Int,
            override val sortType: com.xayah.core.model.SortType,
            val fileList: List<File>,
        ) : Success(opType, selected, selectionMode, isUpdating, sortIndex, sortType)
    }
}
