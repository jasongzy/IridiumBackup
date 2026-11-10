package com.xayah.feature.main.restore

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavHostController
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.datastore.readLastRestoreTime
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.Target
import com.xayah.core.model.TaskStorage
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.navigateSingle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

data class IndexUiState(
    val storageType: StorageMode,
    val cloudEntity: CloudEntity?,
    val packages: List<PackageEntity>,
    val packagesSize: String,
    val medium: List<MediaEntity>,
    val mediumSize: String,
    val autoSelectFirstCloud: Boolean = true,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object UpdateApps : IndexUiIntent()
    data object UpdateFiles : IndexUiIntent()
    data class ToList(val target: Target, val navController: NavHostController) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pkgRepo: PackageRepository,
    private val mediaRepo: MediaRepository,
    private val cloudRepo: CloudRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        storageType = StorageMode.Local,
        cloudEntity = null,
        packages = listOf(),
        packagesSize = "",
        medium = listOf(),
        mediumSize = "",
    )
) {
    fun selectCloudAccount(account: CloudEntity?) {
        updateState { it.copy(cloudEntity = account, autoSelectFirstCloud = false) }
    }

    fun selectStorage(index: Int) {
        updateState { it.copy(storageType = StorageMode.entries[index]) }
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        val storage = when (state.storageType) {
            StorageMode.Local -> TaskStorage("", context.readBackupSavePath().first())
            StorageMode.Cloud -> state.cloudEntity?.let { TaskStorage(it.name, it.remote) }
        }
        when (intent) {
            IndexUiIntent.UpdateApps -> {
                val packages = storage?.let { pkgRepo.queryPackages(OpType.RESTORE, it.cloud, it.backupDir) }.orEmpty()
                val size = packages.sumOf { it.displayStatsBytes }.formatSize()
                updateState { it.copy(packages = packages, packagesSize = size) }
            }

            IndexUiIntent.UpdateFiles -> {
                val files = storage?.let { mediaRepo.query(OpType.RESTORE, it.cloud, it.backupDir) }.orEmpty()
                val size = files.sumOf { it.displayStatsBytes }.formatSize()
                updateState { it.copy(medium = files, mediumSize = size) }
            }

            is IndexUiIntent.ToList -> if (storage != null) withMainContext {
                intent.navController.navigateSingle(MainRoutes.List.getRoute(
                    target = intent.target, opType = OpType.RESTORE, cloudName = storage.cloud, backupDir = storage.backupDir,
                ))
            }
        }
    }

    private val _lastRestoreTime: Flow<Long> = context.readLastRestoreTime().flowOnIO()
    val lastRestoreTimeState: StateFlow<Long> = _lastRestoreTime.stateInScope(0)

    val accounts = cloudRepo.clouds.stateInScope(emptyList())
}
