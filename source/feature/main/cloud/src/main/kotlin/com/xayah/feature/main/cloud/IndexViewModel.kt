package com.xayah.feature.main.cloud

import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.getCloud
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class IndexUiState(
    val isProcessing: Boolean,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data class TestConnection(val entity: CloudEntity) : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    private val cloudRepo: CloudRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState(isProcessing = false)) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.TestConnection -> {
                runCloudOperation(
                    processing = cloudRepo.getString(R.string.processing),
                    setProcessing = { busy -> updateState { it.copy(isProcessing = busy) } },
                    success = cloudRepo.getString(R.string.connection_established),
                ) {
                    intent.entity.getCloud().testConnection()
                }
            }
        }
    }

    private val _accounts: Flow<List<CloudEntity>> = cloudRepo.clouds.flowOnIO()
    val accounts: StateFlow<List<CloudEntity>> = _accounts.stateInScope(listOf())
}
