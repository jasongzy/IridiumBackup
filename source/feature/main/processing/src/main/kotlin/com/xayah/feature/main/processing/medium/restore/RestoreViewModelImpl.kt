package com.xayah.feature.main.processing.medium.restore

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.medium.restore.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.medium.restore.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractMediumProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.LoadRestoreSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class RestoreViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mMediaRepo: MediaRepository,
    private val mCloudRepo: CloudRepository,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val args: SavedStateHandle,
) : AbstractMediumProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            LoadRestoreSource -> {
                val cloud = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME).orEmpty()
                val account = if (cloud.isEmpty()) null else mCloudRepo.queryByName(cloud)
                val directory = args.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE)?.takeIf(String::isNotBlank)
                    ?: if (cloud.isEmpty()) mContext.localBackupSaveDir() else account?.remote
                val storage = if (cloud.isEmpty()) StorageMode.Local else StorageMode.Cloud
                updateState { it.copy(storageType = storage, cloudEntity = account, backupDir = directory) }
                _medium.value = if (directory != null && (cloud.isEmpty() || account != null)) {
                    mMediaRepo.queryActivated(OpType.RESTORE, cloud, directory)
                } else emptyList()
            }

            is FinishSetup -> finishSetup(state) {
                intent.navController.popBackStack()
                intent.navController.navigateSingle(MainRoutes.MediumRestoreProcessing.route)
            }

            else -> Unit
        }
    }

    private val _medium = MutableStateFlow<List<MediaEntity>?>(null)
    val medium = _medium.asStateFlow()
}
