package com.xayah.feature.main.processing.packages.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.PackageRequestStore
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.backup.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.backup.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class BackupViewModelImpl @Inject constructor(
    @ApplicationContext mContext: Context,
    mRootService: RemoteRootService,
    taskRepo: TaskRepository,
    mCloudRepo: CloudRepository,
    requestStore: PackageRequestStore,
    private val listDataRepo: ListDataRepo,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, taskRepo, mLocalService, mCloudService) {
    private val _request = MutableStateFlow(requestStore.takeBackup())
    val request = _request.asStateFlow()

    fun setBackupOptions(value: PackageTaskRequest.Backup) {
        if (!isStarting.value) _request.value = value
    }

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is FinishSetup -> {
                finishSetup(state) {
                    confirmedRequest = checkNotNull(request.value)
                    listDataRepo.clearAppSelection()
                    intent.navController.popBackStack()
                    intent.navController.navigateSingle(MainRoutes.PackagesBackupProcessing.route)
                }
            }

            else -> {

            }
        }
    }


    val accounts = mCloudRepo.clouds.stateInScope(emptyList())
}
