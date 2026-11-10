package com.xayah.feature.main.processing.medium.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.OpType
import com.xayah.core.model.util.formatSize
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.medium.backup.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.medium.backup.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractMediumProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.UpdateFiles
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
    mTaskRepo: TaskRepository,
    private val mMediaRepo: MediaRepository,
    mCloudRepo: CloudRepository,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
) : AbstractMediumProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateFiles -> {
                val medium = mMediaRepo.queryActivated(OpType.BACKUP)
                _mediumSize.value = medium.sumOf { it.displayStatsBytes }.formatSize()
            }

            is FinishSetup -> finishSetup(state) {
                intent.navController.popBackStack()
                intent.navController.navigateSingle(MainRoutes.MediumBackupProcessing.route)
            }

            else -> Unit
        }
    }

    private val _mediumSize = MutableStateFlow("")

    val accounts = mCloudRepo.clouds.stateInScope(emptyList())
    val mediumSize = _mediumSize.asStateFlow()
}
