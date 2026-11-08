package com.xayah.feature.main.settings.titanium

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.TitaniumImportRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TitaniumImportUiState(
    val backupPath: String = "",
    val labelPath: String = "",
    val mode: ImportMode = ImportMode.BACKUPS,
    val stage: ImportStage = ImportStage.IDLE,
    val cancelling: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val backupCandidates: List<TitaniumImportRepository.BackupCandidate> = emptyList(),
    val labelCandidates: List<TitaniumImportRepository.LabelCandidate> = emptyList(),
    val selectedBackupIds: Set<String> = emptySet(),
    val selectedLabels: Set<String> = emptySet(),
    val searchQuery: String = "",
    val backupResults: List<TitaniumImportRepository.BackupResult> = emptyList(),
    val labelResults: List<TitaniumImportRepository.LabelResult> = emptyList(),
    val error: String? = null,
) : UiState {
    internal val hasImportableCandidates get() = when (mode) {
        ImportMode.BACKUPS -> backupCandidates.any { it.isComplete }
        ImportMode.LABELS -> labelCandidates.isNotEmpty()
    }
    internal val visibleBackups get() = backupCandidates.filter { matchesImportSearch(searchQuery, it.label, it.packageName) }

    internal fun selectBackups(selected: Boolean, packageName: String? = null): TitaniumImportUiState {
        val ids = visibleBackups.filter { it.isComplete && (packageName == null || it.packageName == packageName) }
            .mapTo(mutableSetOf()) { it.id }
        return copy(selectedBackupIds = if (selected) selectedBackupIds + ids else selectedBackupIds - ids)
    }
}

enum class ImportMode { BACKUPS, LABELS }
enum class ImportStage { IDLE, SCANNING, PREVIEW, IMPORTING, COMPLETE }

internal fun matchesImportSearch(query: String, label: String, packageName: String): Boolean = query.isBlank() ||
    label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)

val TitaniumImportUiState.isBusy: Boolean
    get() = stage == ImportStage.SCANNING || stage == ImportStage.IMPORTING

sealed class TitaniumImportIntent : UiIntent {
    data class SelectPath(val path: String) : TitaniumImportIntent()
    data class SetMode(val mode: ImportMode) : TitaniumImportIntent()
    data class ToggleBackup(val id: String) : TitaniumImportIntent()
    data class SelectBackupGroup(val packageName: String, val selected: Boolean) : TitaniumImportIntent()
    data class ToggleLabel(val label: String) : TitaniumImportIntent()
    data class SelectAll(val selected: Boolean) : TitaniumImportIntent()
    data class SetSearchQuery(val value: String) : TitaniumImportIntent()
    data object Scan : TitaniumImportIntent()
    data object Import : TitaniumImportIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class TitaniumImportViewModel @Inject constructor(
    private val repository: TitaniumImportRepository,
) : BaseViewModel<TitaniumImportUiState, TitaniumImportIntent, IndexUiEffect>(TitaniumImportUiState()) {
    private var operationJob: Job? = null
    private var previewId = UUID.randomUUID()
    private val modeStates = mutableMapOf<ImportMode, TitaniumImportUiState>()

    init {
        viewModelScope.coroutineContext.job.invokeOnCompletion { releasePreview(previewId) }
        launchOnIO {
            val backupPath = repository.detectBackupDir().orEmpty()
            val labelPath = repository.detectLabelDatabase().orEmpty()
            updateState {
                it.copy(
                    backupPath = it.backupPath.ifEmpty { backupPath },
                    labelPath = it.labelPath.ifEmpty { labelPath },
                )
            }
        }
    }

    override suspend fun onEvent(state: TitaniumImportUiState, intent: TitaniumImportIntent) = withMainContext {
        val current = uiState.value
        if ((current.isBusy || current.cancelling) && intent !is TitaniumImportIntent.SetSearchQuery) return@withMainContext
        when (intent) {
            is TitaniumImportIntent.SetMode -> setMode(current, intent.mode)
            is TitaniumImportIntent.SelectPath -> {
                if (current.mode == ImportMode.BACKUPS) {
                    resetCurrent { it.copy(backupPath = intent.path) }
                    releasePreview(previewId)
                    previewId = UUID.randomUUID()
                } else {
                    resetCurrent { it.copy(labelPath = intent.path) }
                }
            }
            is TitaniumImportIntent.ToggleBackup -> toggleBackup(current, intent.id)
            is TitaniumImportIntent.SelectBackupGroup -> updateState { it.selectBackups(intent.selected, intent.packageName) }
            is TitaniumImportIntent.ToggleLabel -> toggleLabel(intent.label)
            is TitaniumImportIntent.SelectAll -> selectAll(intent.selected)
            is TitaniumImportIntent.SetSearchQuery -> updateState { it.copy(searchQuery = intent.value) }
            TitaniumImportIntent.Scan -> startScan(current)
            TitaniumImportIntent.Import -> startImport(current)
        }
    }

    fun cancelImport() {
        launchOnMain {
            updateState { it.copy(cancelling = true) }
            operationJob?.cancelAndJoin()
            updateState { it.copy(cancelling = false) }
        }
    }

    private fun releasePreview(id: UUID) {
        Dispatchers.IO.asExecutor().execute { repository.clearPreviewCache(id) }
    }

    private fun setMode(state: TitaniumImportUiState, mode: ImportMode) {
        if (mode == state.mode) return
        modeStates[state.mode] = state
        val restored = modeStates.remove(mode) ?: TitaniumImportUiState(mode = mode)
        updateState { restored.copy(backupPath = it.backupPath, labelPath = it.labelPath) }
    }

    private fun resetCurrent(selectPath: (TitaniumImportUiState) -> TitaniumImportUiState) {
        updateState {
            selectPath(TitaniumImportUiState(mode = it.mode, backupPath = it.backupPath, labelPath = it.labelPath))
        }
    }

    private fun startScan(state: TitaniumImportUiState) {
        if (operationJob?.isActive == true) return
        updateState { it.copy(stage = ImportStage.SCANNING, cancelling = false, completed = 0, total = 0, error = null) }
        operationJob = viewModelScope.launch {
            try {
                withIOContext {
                    when (state.mode) {
                        ImportMode.BACKUPS -> {
                            val candidates = repository.scanBackups(state.backupPath, previewId) { completed, total ->
                                updateState { it.copy(completed = completed, total = total) }
                            }
                            updateState {
                                it.copy(
                                    stage = ImportStage.PREVIEW,
                                    backupCandidates = candidates,
                                    selectedBackupIds = candidates.filter { it.isComplete && !it.imported }.mapTo(mutableSetOf()) { it.id },
                                )
                            }
                        }
                        ImportMode.LABELS -> {
                            val candidates = repository.scanLabels(state.labelPath)
                            updateState {
                                it.copy(
                                    stage = ImportStage.PREVIEW,
                                    completed = candidates.size,
                                    total = candidates.size,
                                    labelCandidates = candidates,
                                    selectedLabels = candidates.filterNot { it.imported }.mapTo(mutableSetOf()) { it.name },
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (state.mode == ImportMode.BACKUPS) repository.clearPreviewCache(previewId)
                    updateState { it.copy(stage = ImportStage.IDLE) }
                }
            } catch (error: Exception) {
                if (state.mode == ImportMode.BACKUPS) withIOContext { repository.clearPreviewCache(previewId) }
                updateState { it.copy(stage = ImportStage.IDLE, error = error.message.orEmpty().ifBlank { error.javaClass.simpleName }) }
            }
        }
    }

    private fun startImport(state: TitaniumImportUiState) {
        if (operationJob?.isActive == true || state.stage != ImportStage.PREVIEW) return
        updateState { it.copy(stage = ImportStage.IMPORTING, cancelling = false, completed = 0, total = 0, backupResults = emptyList(), labelResults = emptyList(), error = null) }
        operationJob = viewModelScope.launch {
            try {
                withIOContext {
                    when (state.mode) {
                        ImportMode.BACKUPS -> repository.importBackups(
                            state.backupCandidates.filter { it.id in state.selectedBackupIds }
                        ) { completed, total, result ->
                            updateState {
                                it.copy(
                                    completed = completed,
                                    total = total,
                                    backupResults = if (result == null) it.backupResults else it.backupResults + result,
                                )
                            }
                        }
                        ImportMode.LABELS -> repository.importLabels(
                            state.labelCandidates.filter { it.name in state.selectedLabels }
                        ) { completed, total, result ->
                            updateState {
                                it.copy(
                                    completed = completed,
                                    total = total,
                                    labelResults = if (result == null) it.labelResults else it.labelResults + result,
                                )
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                updateState { it.copy(error = error.message.orEmpty().ifBlank { error.javaClass.simpleName }) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (state.mode == ImportMode.BACKUPS) repository.clearPreviewCache(previewId)
                    updateState { it.copy(stage = ImportStage.COMPLETE) }
                }
            }
        }
    }

    private fun toggleBackup(state: TitaniumImportUiState, id: String) {
        val candidate = state.backupCandidates.firstOrNull { it.id == id } ?: return
        if (!candidate.isComplete) return
        updateState { it.copy(selectedBackupIds = it.selectedBackupIds.toggle(id)) }
    }

    private fun toggleLabel(label: String) {
        updateState { it.copy(selectedLabels = it.selectedLabels.toggle(label)) }
    }

    private fun selectAll(selected: Boolean) {
        updateState { state ->
            when (state.mode) {
                ImportMode.BACKUPS -> state.selectBackups(selected)
                ImportMode.LABELS -> state.copy(
                    selectedLabels = if (selected) state.labelCandidates.mapTo(mutableSetOf()) { it.name } else emptySet()
                )
            }
        }
    }

    private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value
}
