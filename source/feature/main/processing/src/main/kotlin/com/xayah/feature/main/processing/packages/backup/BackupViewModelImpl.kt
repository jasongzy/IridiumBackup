package com.xayah.feature.main.processing.packages.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.BackupRequestStore
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.backup.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.backup.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class BackupViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    mRootService: RemoteRootService,
    taskRepo: TaskRepository,
    private val mCloudRepo: CloudRepository,
    private val backupRequestStore: BackupRequestStore,
    private val listDataRepo: ListDataRepo,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, taskRepo, mLocalService, mCloudService) {
    fun selectStorage(index: Int) {
        updateState { it.copy(storageIndex = index, storageType = if (index == 0) StorageMode.Local else StorageMode.Cloud) }
    }

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                val requestedPackages = backupRequestStore.packages.value
                _packages.value = requestedPackages
                _packagesSize.value = requestedPackages.sumOf { it.selectedDisplayStatsBytes }.toDouble().formatSize()
            }

            is SetCloudEntity -> {
                updateState { it.copy(cloudEntity = null) }
                mContext.saveCloudActivatedAccountName(intent.name)
                val account = mCloudRepo.queryByName(intent.name)
                updateState { it.copy(cloudEntity = account) }
            }

            is FinishSetup -> {
                finishSetup(state) {
                    listDataRepo.clearAppSelection()
                    intent.navController.popBackStack()
                    intent.navController.navigateSingle(MainRoutes.PackagesBackupProcessing.route)
                }
            }

            else -> {

            }
        }
    }

    private val _accounts: Flow<List<DialogRadioItem<Any>>> = mCloudRepo.clouds.map { entities ->
        entities.map {
            DialogRadioItem(
                enum = Any(),
                title = it.name,
                desc = it.user,
            )
        }
    }.flowOnIO()
    private val _packages: MutableStateFlow<List<PackageEntity>> = MutableStateFlow(listOf())
    private val _packagesSize: MutableStateFlow<String> = MutableStateFlow(mContext.getString(R.string.loading))

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packagesSize.stateInScope(mContext.getString(R.string.loading))
}
