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
    data class RestoreWarnings(val damaged: List<PackageEntity>, val keystore: List<PackageEntity>)

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
                _preview.value = PackageTaskRequest.Restore(
                    packages, appBackupRepository.getRestoreSizeBytes("$cloud:$backupSaveDir", packages),
                )
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
    private val _preview = MutableStateFlow<PackageTaskRequest.Restore?>(null)
    private val _restoreUsers = MutableStateFlow(listOf(backupUser))
    private val _warnings = MutableStateFlow<RestoreWarnings?>(null)
    private val _isCheckingRestore = MutableStateFlow(false)

    val preview: StateFlow<PackageTaskRequest.Restore?> = _preview
    val restoreUsers: StateFlow<List<DialogRadioItem<Int>>> = _restoreUsers
    val warnings: StateFlow<RestoreWarnings?> = _warnings
    val isCheckingRestore: StateFlow<Boolean> = _isCheckingRestore

    fun continueRestore(restoreUser: Int, navController: NavController) {
        if (isStarting.value || !_isCheckingRestore.compareAndSet(false, true)) return
        launchOnIO {
            try {
                val request = _preview.value?.copy(userId = restoreUser) ?: return@launchOnIO
                confirmedRequest = request
                val damaged = appBackupRepository.knownDamagedBackups(request.packages)
                val keystore = request.packages.filter { app ->
                    app.extraInfo.hasKeystore &&
                        app.selectionFlag and PackageEntity.FLAG_DATA != 0 &&
                        mRootService.getPackageUid(app.packageName, request.targetUser(app)) == -1
                }
                if (damaged.isEmpty() && keystore.isEmpty()) {
                    emitIntent(FinishSetup(navController))
                } else {
                    _warnings.value = RestoreWarnings(damaged, keystore)
                }
            } finally {
                _isCheckingRestore.value = false
            }
        }
    }

    fun confirmWarnings(navController: NavController) {
        _warnings.value = null
        emitIntentOnIO(FinishSetup(navController))
    }

    fun dismissWarnings() {
        confirmedRequest = null
        _warnings.value = null
    }
}
