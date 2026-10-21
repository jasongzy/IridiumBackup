package com.xayah.feature.main.restore.reload

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.feature.main.restore.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IndexUiState(
    val isLoading: Boolean,
    val text: String,
    val completed: Int,
    val total: Int,
    val indexed: Int,
    val results: List<AppBackupRepository.RebuildResult>,
) : UiState

sealed class IndexUiIntent : UiIntent {
    data object Rebuild : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appBackupRepository: AppBackupRepository,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(
    IndexUiState(
        isLoading = false,
        text = context.getString(R.string.idle),
        completed = 0,
        total = 0,
        indexed = 0,
        results = emptyList(),
    )
) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            IndexUiIntent.Rebuild -> {
                if (uiState.value.isLoading) return
                updateState { it.copy(isLoading = true, completed = 0, total = 0, indexed = 0, results = emptyList()) }
                try {
                    val report = withContext(Dispatchers.IO) {
                        appBackupRepository.rebuildLocalIndex { completed, total, indexed ->
                            updateState { it.copy(completed = completed, total = total, indexed = indexed) }
                        }
                    }
                    updateState {
                        it.copy(text = context.getString(R.string.rebuild_finished, report.results.size), results = report.results)
                    }
                    Toast.makeText(context, uiState.value.text, Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    updateState {
                        it.copy(text = "${context.getString(R.string.failed)}: ${error.localizedMessage ?: error.javaClass.simpleName}")
                    }
                } finally {
                    updateState { it.copy(isLoading = false) }
                }
            }
        }
    }
}
