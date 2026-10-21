package com.xayah.feature.main.settings.titanium

import android.app.Activity
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.TitaniumImportRepository
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.libpickyou.ui.model.PickerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

data class TitaniumImportUiState(
    val backupPath: String,
    val labelPath: String,
    val mode: ImportMode,
    val stage: ImportStage,
    val cancelling: Boolean,
    val completed: Int,
    val total: Int,
    val backupCandidates: List<TitaniumImportRepository.BackupCandidate>,
    val labelCandidates: List<TitaniumImportRepository.LabelCandidate>,
    val selectedBackupIds: Set<String>,
    val selectedLabels: Set<String>,
    val searchQuery: String,
    val backupResults: List<TitaniumImportRepository.BackupResult>,
    val labelResults: List<TitaniumImportRepository.LabelResult>,
    val error: String?,
) : UiState

enum class ImportMode { BACKUPS, LABELS }
enum class ImportStage { IDLE, SCANNING, PREVIEW, IMPORTING, COMPLETE }

val TitaniumImportUiState.isBusy: Boolean
    get() = stage == ImportStage.SCANNING || stage == ImportStage.IMPORTING

sealed class TitaniumImportIntent : UiIntent {
    data class SelectBackupPath(val activity: Activity) : TitaniumImportIntent()
    data class SelectLabelPath(val activity: Activity) : TitaniumImportIntent()
    data class SetMode(val mode: ImportMode) : TitaniumImportIntent()
    data class ToggleBackup(val id: String) : TitaniumImportIntent()
    data class ToggleBackupGroup(val packageName: String) : TitaniumImportIntent()
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
) : BaseViewModel<TitaniumImportUiState, TitaniumImportIntent, IndexUiEffect>(
    TitaniumImportUiState(
        backupPath = "",
        labelPath = "",
        mode = ImportMode.BACKUPS,
        stage = ImportStage.IDLE,
        cancelling = false,
        completed = 0,
        total = 0,
        backupCandidates = emptyList(),
        labelCandidates = emptyList(),
        selectedBackupIds = emptySet(),
        selectedLabels = emptySet(),
        searchQuery = "",
        backupResults = emptyList(),
        labelResults = emptyList(),
        error = null,
    )
) {
    private var operationJob: Job? = null
    private val modeStates = mutableMapOf<ImportMode, TitaniumImportUiState>()

    init {
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
            is TitaniumImportIntent.SelectBackupPath -> selectPath(intent.activity, PickerType.DIRECTORY) { path ->
                resetCurrent { it.copy(backupPath = path) }
            }
            is TitaniumImportIntent.SelectLabelPath -> selectPath(intent.activity, PickerType.FILE) { path ->
                resetCurrent { it.copy(labelPath = path) }
            }
            is TitaniumImportIntent.ToggleBackup -> toggleBackup(current, intent.id)
            is TitaniumImportIntent.ToggleBackupGroup -> toggleBackupGroup(current, intent.packageName)
            is TitaniumImportIntent.ToggleLabel -> toggleLabel(intent.label)
            is TitaniumImportIntent.SelectAll -> selectAll(current, intent.selected)
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

    fun discardPreview() {
        repository.clearPreviewCache()
    }

    private fun setMode(state: TitaniumImportUiState, mode: ImportMode) {
        if (mode == state.mode) return
        modeStates[state.mode] = state
        val restored = modeStates[mode] ?: state.copy(
            mode = mode,
            stage = ImportStage.IDLE,
            cancelling = false,
            completed = 0,
            total = 0,
            backupCandidates = emptyList(),
            labelCandidates = emptyList(),
            selectedBackupIds = emptySet(),
            selectedLabels = emptySet(),
            searchQuery = "",
            backupResults = emptyList(),
            labelResults = emptyList(),
            error = null,
        )
        updateState { restored.copy(backupPath = it.backupPath, labelPath = it.labelPath) }
    }

    private fun resetCurrent(selectPath: (TitaniumImportUiState) -> TitaniumImportUiState) {
        modeStates.remove(uiState.value.mode)
        updateState {
            selectPath(it).copy(
                stage = ImportStage.IDLE,
                cancelling = false,
                completed = 0,
                total = 0,
                backupCandidates = emptyList(),
                labelCandidates = emptyList(),
                selectedBackupIds = emptySet(),
                selectedLabels = emptySet(),
                searchQuery = "",
                backupResults = emptyList(),
                labelResults = emptyList(),
                error = null,
            )
        }
    }

    private fun startScan(state: TitaniumImportUiState) {
        if (operationJob?.isActive == true) return
        updateState { it.copy(stage = ImportStage.SCANNING, cancelling = false, completed = 0, total = 0, error = null) }
        operationJob = launchOnIO {
            try {
                when (state.mode) {
                    ImportMode.BACKUPS -> {
                        val candidates = repository.scanBackups(state.backupPath) { completed, total ->
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
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    if (state.mode == ImportMode.BACKUPS) repository.clearPreviewCache()
                    updateState { it.copy(stage = ImportStage.IDLE) }
                }
            } catch (error: Throwable) {
                if (state.mode == ImportMode.BACKUPS) repository.clearPreviewCache()
                updateState { it.copy(stage = ImportStage.IDLE, error = error.message.orEmpty()) }
            }
        }
    }

    private fun startImport(state: TitaniumImportUiState) {
        if (operationJob?.isActive == true || state.stage != ImportStage.PREVIEW) return
        updateState { it.copy(stage = ImportStage.IMPORTING, cancelling = false, completed = 0, total = 0, backupResults = emptyList(), labelResults = emptyList(), error = null) }
        operationJob = launchOnIO {
            try {
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
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                updateState { it.copy(error = error.message.orEmpty()) }
            } finally {
                withContext(NonCancellable) {
                    if (state.mode == ImportMode.BACKUPS) repository.clearPreviewCache()
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

    private fun toggleBackupGroup(state: TitaniumImportUiState, packageName: String) {
        val ids = state.backupCandidates.filter { it.packageName == packageName && it.isComplete }.mapTo(mutableSetOf()) { it.id }
        updateState {
            it.copy(selectedBackupIds = if (ids.all(it.selectedBackupIds::contains)) it.selectedBackupIds - ids else it.selectedBackupIds + ids)
        }
    }

    private fun toggleLabel(label: String) {
        updateState { it.copy(selectedLabels = it.selectedLabels.toggle(label)) }
    }

    private fun selectAll(state: TitaniumImportUiState, selected: Boolean) {
        updateState {
            when (state.mode) {
                ImportMode.BACKUPS -> it.copy(
                    selectedBackupIds = if (selected) state.backupCandidates.filter { it.isComplete }.mapTo(mutableSetOf()) { it.id } else emptySet()
                )
                ImportMode.LABELS -> it.copy(
                    selectedLabels = if (selected) state.labelCandidates.mapTo(mutableSetOf()) { it.name } else emptySet()
                )
            }
        }
    }

    private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

    override fun onCleared() {
        repository.clearPreviewCache()
        super.onCleared()
    }

    private suspend fun selectPath(activity: Activity, pickerType: PickerType, onSelected: suspend (String) -> Unit) {
        withMainContext {
            PickYouLauncher(
                checkPermission = true,
                title = activity.getString(com.xayah.feature.main.settings.R.string.select_source),
                pickerType = pickerType,
                permissionType = PermissionType.ROOT,
            ).apply {
                launch(activity) { path ->
                    launchOnIO {
                        if (pickerType == PickerType.DIRECTORY) repository.clearPreviewCache()
                        withMainContext { onSelected(path) }
                    }
                }
            }
        }
    }
}
