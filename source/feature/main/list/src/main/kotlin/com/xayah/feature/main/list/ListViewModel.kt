package com.xayah.feature.main.list

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.PackageRequestStore
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.data.repository.ListData
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.OpType
import com.xayah.core.model.Target
import com.xayah.core.model.util.of
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.launchOnDefault
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.work.WorkManagerInitializer
import com.xayah.feature.main.list.ListUiState.Loading
import com.xayah.feature.main.list.ListUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val listDataRepo: ListDataRepo,
    private val requestStore: PackageRequestStore,
    private val directoryRepository: DirectoryRepository,
    private val appBackupRepository: AppBackupRepository,
) : ViewModel() {
    private var initialRefreshRequested = false
    private val isPreparing = MutableStateFlow(false)
    private val target: Target = savedStateHandle.get<String>(MainRoutes.ARG_TARGET)
        ?.let { Target.valueOf(it) }
        ?: Target.Apps
    private val opType: OpType = OpType.of(savedStateHandle.get<String>(MainRoutes.ARG_OP_TYPE))
    private val cloudName: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_NAME) ?: ""
    private val backupDir: String = savedStateHandle.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE) ?: context.localBackupSaveDir()

    init {
        listDataRepo.initialize(target, opType, cloudName, backupDir)
        if (target == Target.Apps) {
            viewModelScope.launchOnDefault {
                listDataRepo.loadAppSortPreference()
            }
        }
    }

    val uiState: StateFlow<ListUiState> = when (target) {
        Target.Apps -> combine(
            listDataRepo.getListData(),
            listDataRepo.getAppList(),
            combine(appBackupRepository.observeRevisions(), context.readBackupSavePath()) { revisions, path ->
                revisions.filter { it.repositoryId == ":$path" }
            },
            listDataRepo.getSelectedAppKeys(),
            isPreparing,
        ) { data, apps, revisions, selectedKeys, preparing ->
            val listData = data.castTo<ListData.Apps>()
            Success.Apps(
                opType = opType,
                selected = listData.selected,
                selectionMode = listData.selectionMode,
                isUpdating = listData.isUpdating,
                cloudName = cloudName,
                backupDir = backupDir,
                isPreparing = preparing,
                hasSelectedInstalledApps = apps.any { it.selected && it.isInstalled },
                hasSelectedBackups = revisions.any {
                    it.engine == BackupEngine.LEGACY &&
                        AppKey(it.packageName, it.userId) in selectedKeys
                },
            )
        }

        Target.Files -> listDataRepo.getListData().map {
            val listData = it.castTo<ListData.Files>()
            Success.Files(
                opType = opType,
                selected = listData.selected,
                selectionMode = listData.selectionMode,
                isUpdating = listData.isUpdating,
                cloudName = cloudName,
                backupDir = backupDir,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun refresh(initial: Boolean = false) {
        if (initial && initialRefreshRequested) return
        if (initial) initialRefreshRequested = true
        viewModelScope.launchOnDefault {
            if ((uiState.value as? ListUiState.Success)?.isUpdating == true || opType != OpType.BACKUP) return@launchOnDefault
            when (target) {
                Target.Apps -> {
                    val includeSizes = !initial && (listDataRepo.getListData().first() as? ListData.Apps)?.sortIndex == 2
                    WorkManagerInitializer.refreshAppList(context, includeSizes)
                }
                Target.Files -> WorkManagerInitializer.fastInitializeAndUpdateFiles(context)
            }
        }
    }

    fun clearSelection() {
        listDataRepo.clearAppSelection()
    }

    suspend fun nextRoute(): String? = when (target) {
        Target.Apps -> prepareAppRoute {
            val ids = listDataRepo.getSelectedAppIds()
            if (ids.isEmpty()) return@prepareAppRoute null
            when (opType) {
                OpType.BACKUP -> {
                    val hasDirectory = directoryRepository.querySelectedByDirectoryTypeFlow().first() != null
                    if (!requestStore.prepareBackup(ids)) null
                    else if (hasDirectory) MainRoutes.PackagesBackupProcessingGraph.route else MainRoutes.Directory.route
                }
                OpType.RESTORE -> {
                    if (requestStore.prepareRestore(ids, cloudName, backupDir)) restoreRoute(cloudName, backupDir) else null
                }
            }
        }
        Target.Files -> when (opType) {
            OpType.BACKUP -> MainRoutes.MediumBackupProcessingGraph.route
            OpType.RESTORE -> MainRoutes.MediumRestoreProcessingGraph.getRoute(
                cloudName = cloudName, backupDir = backupDir,
            )
        }
    }

    private suspend fun prepareAppRoute(route: suspend () -> String?): String? {
        if (!isPreparing.compareAndSet(false, true)) return null
        return try {
            route().also {
                if (it == null) Toast.makeText(context, com.xayah.core.service.R.string.package_request_expired, Toast.LENGTH_SHORT).show()
            }
        } finally {
            isPreparing.value = false
        }
    }

    private fun restoreRoute(cloud: String, directory: String) = MainRoutes.PackagesRestoreProcessingGraph.getRoute(
        cloudName = cloud, backupDir = directory,
    )

    override fun onCleared() {
        WorkManagerInitializer.cancelAppSizeUpdate(context)
    }

    suspend fun restoreSelectedRoute(): String? = prepareAppRoute {
        val packages = appBackupRepository.latestLocalPackagesForRestore(listDataRepo.getSelectedAppKeys().value)
        val source = packages.firstOrNull()?.indexInfo ?: return@prepareAppRoute null
        requestStore.prepareRestore(packages)
        restoreRoute(source.cloud, source.backupDir)
    }
}

sealed interface ListUiState {
    data object Loading : ListUiState
    sealed class Success(
        open val opType: OpType,
        open val selected: Long,
        open val selectionMode: Boolean,
        open val isUpdating: Boolean,
        open val cloudName: String,
        open val backupDir: String,
    ) : ListUiState {
        data class Apps(
            override val opType: OpType,
            override val selected: Long,
            override val selectionMode: Boolean,
            override val isUpdating: Boolean,
            override val cloudName: String,
            override val backupDir: String,
            val isPreparing: Boolean,
            val hasSelectedInstalledApps: Boolean,
            val hasSelectedBackups: Boolean,
        ) : Success(opType, selected, selectionMode, isUpdating, cloudName, backupDir)

        data class Files(
            override val opType: OpType,
            override val selected: Long,
            override val selectionMode: Boolean,
            override val isUpdating: Boolean,
            override val cloudName: String,
            override val backupDir: String,
        ) : Success(opType, selected, selectionMode, isUpdating, cloudName, backupDir)
    }
}
