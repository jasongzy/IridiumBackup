package com.xayah.feature.main.processing.packages.restore

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.restore.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.restore.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.LogUtil
import com.xayah.core.util.decodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.GetUsers
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class RestoreViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mPkgRepo: PackageRepository,
    private val appBackupRepository: AppBackupRepository,
    private val mCloudRepo: CloudRepository,
    private val listDataRepo: ListDataRepo,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val args: SavedStateHandle,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
    data class KeystoreRiskPackage(val label: String, val packageName: String)

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                val cloud: String
                val backupSaveDir: String
                if (state.storageType == StorageMode.Local) {
                    cloud = ""
                    backupSaveDir = state.backupDir ?: mContext.localBackupSaveDir()
                } else {
                    val account = state.cloudEntity ?: return
                    cloud = account.name
                    backupSaveDir = state.backupDir ?: account.remote
                }
                val packages = mPkgRepo.queryActivated(OpType.RESTORE, cloud, backupSaveDir)
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Query activated apps, cloud: $cloud, backupDir: $backupSaveDir" }
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Queried apps count: ${packages.size}" }
                _packages.value = packages
                val bytes = appBackupRepository.getRevisionSizeBytes(
                    repositoryId = "$cloud:$backupSaveDir",
                    artifactIds = packages.map { it.archivesRelativeDir },
                )
                _packagesSize.value = bytes.toDouble().formatSize()
            }

            is SetCloudEntity -> {
                val name = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
                val backupDir = args.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE)?.decodeURL()?.takeIf { it.isNotBlank() }
                if (name.isNotEmpty()) {
                    emitState(state.copy(storageIndex = 1, storageType = StorageMode.Cloud, cloudEntity = mCloudRepo.queryByName(name), backupDir = backupDir))
                } else {
                    emitState(state.copy(storageIndex = 0, storageType = StorageMode.Local, cloudEntity = null, backupDir = backupDir))
                }
            }

            is FinishSetup -> {
                finishSetup(state) {
                    listDataRepo.clearAppSelection()
                    intent.navController.popBackStack()
                    intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
                }
            }

            is GetUsers -> {
                _restoreUsers.value = listOf(backupUser) + mRootService.getUsers().map { it.id }.distinct().sorted().map {
                    DialogRadioItem(enum = it, title = it.toString())
                }
            }

            else -> {

            }
        }
    }

    private val backupUser = DialogRadioItem(enum = -1, title = mContext.getString(R.string.backup_user))
    private val _packages: MutableStateFlow<List<PackageEntity>> = MutableStateFlow(listOf())
    private val _packagesSize: MutableStateFlow<String> = MutableStateFlow(mContext.getString(R.string.loading))
    private val _restoreUsers = MutableStateFlow(listOf(backupUser))
    private val _keystoreRiskPackages = MutableStateFlow<List<KeystoreRiskPackage>>(emptyList())
    private val _isCheckingKeystoreRisk = MutableStateFlow(false)

    val packages: StateFlow<List<PackageEntity>> = _packages
    val packagesSize: StateFlow<String> = _packagesSize
    val restoreUsers: StateFlow<List<DialogRadioItem<Int>>> = _restoreUsers
    val keystoreRiskPackages: StateFlow<List<KeystoreRiskPackage>> = _keystoreRiskPackages
    val isCheckingKeystoreRisk: StateFlow<Boolean> = _isCheckingKeystoreRisk

    fun continueRestore(restoreUser: Int, navController: NavController) {
        if (isStarting.value || !_isCheckingKeystoreRisk.compareAndSet(false, true)) return
        launchOnIO {
            try {
                val request = PackageTaskRequest.Restore(_packages.value, restoreUser)
                confirmedRequest = request
                val risks = request.packages.filter { app ->
                    app.extraInfo.hasKeystore &&
                        app.selectionFlag and PackageEntity.FLAG_DATA != 0 &&
                        mRootService.getPackageUid(app.packageName, request.targetUser(app)) == -1
                }.map { KeystoreRiskPackage(it.packageInfo.label, it.packageName) }
                if (risks.isEmpty()) {
                    emitIntent(FinishSetup(navController))
                } else {
                    _keystoreRiskPackages.value = risks
                }
            } finally {
                _isCheckingKeystoreRisk.value = false
            }
        }
    }

    fun confirmKeystoreRisk(navController: NavController) {
        _keystoreRiskPackages.value = emptyList()
        emitIntentOnIO(FinishSetup(navController))
    }

    fun dismissKeystoreRisk() {
        confirmedRequest = null
        _keystoreRiskPackages.value = emptyList()
    }
}
