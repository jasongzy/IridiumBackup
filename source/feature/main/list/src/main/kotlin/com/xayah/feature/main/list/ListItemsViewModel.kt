package com.xayah.feature.main.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.repository.LabelFilterMode
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.model.App
import com.xayah.core.model.AppKey
import com.xayah.core.model.DataState
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.Target
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.ColoredLabel
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.decodeURL
import com.xayah.core.util.launchOnDefault
import com.xayah.feature.main.list.ListItemsUiState.Loading
import com.xayah.feature.main.list.ListItemsUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineDispatcher
import java.text.Collator
import javax.inject.Inject

@HiltViewModel
class ListItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    appBackupRepository: AppBackupRepository,
    labelsRepo: LabelsRepo,
    @Dispatcher(Default) defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val target: Target = savedStateHandle.get<String>(MainRoutes.ARG_TARGET)
        ?.decodeURL()?.trim()?.let(Target::valueOf) ?: Target.Apps
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE)?.decodeURL()?.trim())

    val uiState: StateFlow<ListItemsUiState> = when (target) {
        Target.Apps -> combine(
            listDataRepo.getAppList(),
            combine(appBackupRepository.observeApps(), appsRepo.getRestoreApps()) { overviews, restoreApps ->
                overviews to restoreApps
            },
            combine(listDataRepo.getListData(), listDataRepo.getSelectedAppKeys()) { data, keys -> data to keys },
            labelsRepo.getAppRefsFlow(),
            labelsRepo.getColoredLabelsFlow(),
        ) { installedApps, backupState, listState, labelRefs, labels ->
            val (overviews, restoreApps) = backupState
            val (rawListData, selectedKeys) = listState
            val listData = rawListData as ListData.Apps
            val uniqueInstalledApps = installedApps
                .groupBy { it.packageName to it.userId }
                .map { (_, apps) -> apps.minBy { it.id } }
            val overviewMap = overviews.associateBy { it.app.packageName to it.app.userId }
            val xposedModules = restoreApps.asSequence()
                .filter { it.packageInfo.isXposedModule }
                .map { it.packageName to it.userId }
                .toSet()
            val labelMap = labels.associateBy(ColoredLabel::label)
            val labelsByApp = labelRefs.groupBy { it.packageName to it.userId }
                .mapValues { (_, refs) -> refs.mapNotNull { labelMap[it.label] }.distinctBy { it.label }.sortedBy { it.label } }
            val installedItems = uniqueInstalledApps.map { app ->
                val overview = overviewMap[app.packageName to app.userId]
                AppListItem(
                    app = app,
                    revisionCount = overview?.revisionCount ?: 0,
                    latestRevisionAt = overview?.latestRevisionAt,
                    hasApkBackup = overview?.hasApkBackup == true,
                    hasDataBackup = overview?.hasDataBackup == true,
                    hasMatchingApkBackup = overview?.hasMatchingApkBackup == true,
                    labels = labelsByApp[app.packageName to app.userId].orEmpty(),
                    notes = listOf(overview?.app?.note, overview?.revisionNotes).filterNotNull().joinToString("\n"),
                )
            }
            val installedKeys = uniqueInstalledApps.mapTo(mutableSetOf()) { it.packageName to it.userId }
            val selectedUserId = listData.userList.getOrNull(listData.userIndex)?.id
            val archivedItems = overviews.asSequence()
                .filter { !it.app.isInstalled }
                .filter { overview ->
                    overview.revisionCount > 0 ||
                        overview.app.note.isNotEmpty() ||
                        labelsByApp[overview.app.packageName to overview.app.userId].isNullOrEmpty().not()
                }
                .filter { selectedUserId == null || it.app.userId == selectedUserId }
                .filter { it.app.packageName to it.app.userId !in installedKeys }
                .filter { listData.filters.notInstalledApps }
                .filter { overview ->
                    if (overview.app.isSystem) listData.filters.systemApps else listData.filters.nonSystemApps
                }
                .filter { overview ->
                    val appLabels = labelsByApp[overview.app.packageName to overview.app.userId].orEmpty().mapTo(mutableSetOf()) { it.label }
                    val included = listData.labelFilters.filterValues { it == LabelFilterMode.INCLUDE }.keys
                    val excluded = listData.labelFilters.filterValues { it == LabelFilterMode.EXCLUDE }.keys
                    listData.filters.matchesIncludedLabels(appLabels, included) && appLabels.none(excluded::contains)
                }
                .map { overview ->
                    AppListItem(
                        app = App(
                            id = 0,
                            packageName = overview.app.packageName,
                            userId = overview.app.userId,
                            label = overview.app.label,
                            versionName = overview.app.versionName,
                            versionCode = overview.app.versionCode,
                            preserveId = 0,
                            isSystemApp = overview.app.isSystem,
                            isUpdatedSystemApp = false,
                            isXposedModule = overview.app.packageName to overview.app.userId in xposedModules,
                            isFrozen = false,
                            isInstalled = false,
                            firstInstallTime = overview.app.firstInstallTime,
                            lastUpdateTime = overview.app.lastUpdateTime,
                            lastBackupTime = overview.latestRevisionAt ?: 0,
                            dataSizeBytes = 0,
                            selectionFlag = PackageEntity.FLAG_NONE,
                            selected = AppKey(overview.app.packageName, overview.app.userId) in selectedKeys,
                        ),
                        revisionCount = overview.revisionCount,
                        latestRevisionAt = overview.latestRevisionAt,
                        hasApkBackup = overview.hasApkBackup,
                        hasDataBackup = overview.hasDataBackup,
                        hasMatchingApkBackup = overview.hasMatchingApkBackup,
                        labels = labelsByApp[overview.app.packageName to overview.app.userId].orEmpty(),
                        notes = listOf(overview.app.note, overview.revisionNotes).joinToString("\n"),
                    )
                }
                .toList()

            val filteredItems = (installedItems + archivedItems).asSequence()
                .filter { item ->
                    item.app.label.contains(listData.searchQuery, ignoreCase = true) ||
                        item.app.packageName.contains(listData.searchQuery, ignoreCase = true) ||
                        item.notes.contains(listData.searchQuery, ignoreCase = true)
                }
                .filter { if (it.app.isFrozen) listData.filters.frozenApps else listData.filters.unfrozenApps }
                .filter { listData.filters.matchesXposed(it.app.isXposedModule) }
                .filter { listData.filters.hasBackups || it.revisionCount == 0 }
                .filter { listData.filters.hasNoBackups || it.revisionCount > 0 }
                .filter { !listData.filters.singleBackup || it.revisionCount == 1 }
                .filter { !listData.filters.multipleBackups || it.revisionCount >= 2 }
                .filter { opType != OpType.BACKUP || !listData.filters.hasApkBackup || it.hasApkBackup }
                .filter { opType != OpType.BACKUP || !listData.filters.hasNoApkBackup || !it.hasApkBackup }
                .filter { opType != OpType.BACKUP || !listData.filters.hasDataBackup || it.hasDataBackup }
                .filter { opType != OpType.BACKUP || !listData.filters.hasNoDataBackup || !it.hasDataBackup }
                .filter { item ->
                    opType != OpType.BACKUP || !listData.filters.hasNonMatchingApkBackup || (
                        item.app.isInstalled &&
                            item.hasApkBackup &&
                            !item.hasMatchingApkBackup
                        )
                }
                .toList()

            if (listData.searchQuery.isEmpty()) {
                listDataRepo.retainAppSelection(filteredItems.map { it.app.key })
            }
            Success.Apps(
                opType = opType,
                appList = filteredItems.sorted(listData),
                showInstallTime = listData.sortIndex == 1,
                showDataSize = listData.sortIndex == 2,
                showUpdateTime = listData.sortIndex == 3,
            )
        }

        Target.Files -> listDataRepo.getFileList().map {
            Success.Files(
                opType = opType,
                fileList = it,
            )
        }
    }.flowOn(defaultDispatcher).stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun onAppSelectedChanged(key: AppKey, selected: Boolean) {
        viewModelScope.launchOnDefault {
            if (target == Target.Apps) listDataRepo.setAppSelected(key, selected)
        }
    }

    fun onFileSelectedChanged(id: Long, selected: Boolean) {
        viewModelScope.launchOnDefault {
            if (target == Target.Files) filesRepo.selectFile(id, selected)
        }
    }

    fun enterSelection(key: AppKey) {
        viewModelScope.launchOnDefault { listDataRepo.enterAppSelection(key) }
    }

    private fun List<AppListItem>.sorted(listData: ListData.Apps): List<AppListItem> {
        val collator = Collator.getInstance()
        val labelComparator = Comparator<AppListItem> { first, second ->
            collator.compare(first.app.label, second.app.label)
        }
        val comparator = when (listData.sortIndex) {
            1 -> compareValues(listData.sortType) { it.app.firstInstallTime.takeIf { time -> time > 0 } }
            2 -> compareValues(listData.sortType) { item -> item.app.dataSizeBytes.takeIf { item.app.isInstalled } }
            3 -> compareValues(listData.sortType) { it.app.lastUpdateTime.takeIf { time -> time > 0 } }
            4 -> compareValues(listData.sortType) { it.latestRevisionAt?.takeIf { time -> time > 0 } }
            else -> if (listData.sortType == SortType.ASCENDING) {
                labelComparator
            } else {
                labelComparator.reversed()
            }
        }
        return sortedWith(
            comparator
                .then(labelComparator)
                .thenBy { it.app.packageName }
                .thenBy { it.app.userId }
        )
    }

    private fun <T : Comparable<T>> compareValues(
        sortType: SortType,
        selector: (AppListItem) -> T?,
    ): Comparator<AppListItem> = Comparator { first, second ->
        val firstValue = selector(first)
        val secondValue = selector(second)
        when {
            firstValue == null -> if (secondValue == null) 0 else 1
            secondValue == null -> -1
            sortType == SortType.ASCENDING -> firstValue.compareTo(secondValue)
            else -> secondValue.compareTo(firstValue)
        }
    }

    fun onChangeFlag(id: Long, flag: Int) {
        viewModelScope.launchOnDefault {
            when (flag) {
                PackageEntity.FLAG_APK -> {
                    appsRepo.selectDataItems(
                        id = id,
                        apk = DataState.NotSelected,
                        user = DataState.Selected,
                        userDe = DataState.Selected,
                        data = DataState.Selected,
                        obb = DataState.Selected,
                        media = DataState.Selected,
                    )
                }

                PackageEntity.FLAG_ALL -> {
                    appsRepo.selectDataItems(
                        id = id,
                        apk = DataState.Selected,
                        user = DataState.NotSelected,
                        userDe = DataState.NotSelected,
                        data = DataState.NotSelected,
                        obb = DataState.NotSelected,
                        media = DataState.NotSelected,
                    )
                }

                else -> {
                    appsRepo.selectDataItems(
                        id = id,
                        apk = DataState.Selected,
                        user = DataState.Selected,
                        userDe = DataState.Selected,
                        data = DataState.Selected,
                        obb = DataState.Selected,
                        media = DataState.Selected,
                    )
                }
            }
        }
    }
}

sealed interface ListItemsUiState {
    data object Loading : ListItemsUiState
    sealed class Success(
        open val opType: OpType,
    ) : ListItemsUiState {
        data class Apps(
            override val opType: OpType,
            val appList: List<AppListItem>,
            val showInstallTime: Boolean,
            val showDataSize: Boolean,
            val showUpdateTime: Boolean,
        ) : Success(opType)

        data class Files(
            override val opType: OpType,
            val fileList: List<File>,
        ) : Success(opType)
    }
}

@Immutable
data class AppListItem(
    val app: App,
    val revisionCount: Int,
    val latestRevisionAt: Long?,
    val hasApkBackup: Boolean,
    val hasDataBackup: Boolean,
    val hasMatchingApkBackup: Boolean,
    val labels: List<ColoredLabel>,
    val notes: String,
)
