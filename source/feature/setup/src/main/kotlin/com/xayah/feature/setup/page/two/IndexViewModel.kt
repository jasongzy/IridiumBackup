package com.xayah.feature.setup.page.two

import android.app.Activity
import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.datastore.saveAppVersionName
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.ActivityUtil
import com.xayah.core.work.WorkManagerInitializer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class IndexUiState(
    val detectedBackupPath: String? = null,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object PrepareDirectory : IndexUiIntent()
    data class ToMain(val context: Activity, val startDestination: String? = null) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    private val directoryRepository: DirectoryRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState()) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            IndexUiIntent.PrepareDirectory -> {
                emitState(state.copy(detectedBackupPath = directoryRepository.update()))
            }
            is IndexUiIntent.ToMain -> {
                val context = intent.context
                WorkManagerInitializer.fullInitializeAndUpdateApps(context)
                context.saveAppVersionName()
                context.startActivity(
                    Intent(context, ActivityUtil.classMainActivity).apply {
                        intent.startDestination?.let { putExtra(ActivityUtil.EXTRA_START_DESTINATION, it) }
                    }
                )
                context.finish()
            }
        }
    }
}
