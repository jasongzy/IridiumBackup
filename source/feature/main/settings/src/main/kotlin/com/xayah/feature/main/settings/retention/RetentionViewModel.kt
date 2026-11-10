package com.xayah.feature.main.settings.retention

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.AppBackupRepository.RetentionResult
import com.xayah.feature.main.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RetentionUiState(
    val retainCount: String = "1",
    val hasScanned: Boolean = false,
    val isScanning: Boolean = false,
    val isDeleting: Boolean = false,
    val completed: Int = 0,
    val deleteTotal: Int = 0,
    val error: String? = null,
    val candidates: List<AppBackupRepository.RetentionCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
)

@HiltViewModel
class RetentionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppBackupRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RetentionUiState())
    val uiState = _uiState.asStateFlow()

    fun setRetainCount(value: String) {
        if (value.isNotEmpty() && value.any { !it.isDigit() }) return
        _uiState.value = _uiState.value.copy(
            retainCount = value,
            error = null,
            hasScanned = false,
            candidates = emptyList(),
            selectedIds = emptySet(),
        )
    }

    fun toggle(id: String) {
        val state = _uiState.value
        if (state.candidates.none { it.revision.id == id && it.removable }) return
        _uiState.value = state.copy(
            selectedIds = if (id in state.selectedIds) state.selectedIds - id else state.selectedIds + id,
        )
    }

    fun toggleGroup(packageName: String, userId: Int) {
        val state = _uiState.value
        val ids = state.candidates
            .filter { it.removable && it.revision.packageName == packageName && it.revision.userId == userId }
            .mapTo(mutableSetOf()) { it.revision.id }
        _uiState.value = state.copy(
            selectedIds = if (ids.all(state.selectedIds::contains)) state.selectedIds - ids else state.selectedIds + ids,
        )
    }

    fun selectAll(selected: Boolean) {
        val state = _uiState.value
        _uiState.value = state.copy(
            selectedIds = if (selected) state.candidates.filter { it.removable }.mapTo(mutableSetOf()) { it.revision.id }
            else emptySet(),
        )
    }

    fun scan() {
        val count = _uiState.value.retainCount.toIntOrNull()?.takeIf { it >= 1 } ?: return
        if (_uiState.value.isScanning || _uiState.value.isDeleting) return
        _uiState.value = _uiState.value.copy(isScanning = true, error = null, deleteTotal = 0)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateCandidates(repository.getLocalRetentionCandidates(count))
            } catch (e: Exception) {
                reportError(e)
            } finally {
                _uiState.value = _uiState.value.copy(isScanning = false)
            }
        }
    }

    fun deleteSelected() {
        val state = _uiState.value
        val retainCount = state.retainCount.toIntOrNull()?.takeIf { it >= 1 } ?: return
        val candidateIds = state.selectedIds
        if (candidateIds.isEmpty() || state.isScanning || state.isDeleting) return
        _uiState.value = _uiState.value.copy(
            isDeleting = true,
            error = null,
            completed = 0,
            deleteTotal = candidateIds.size,
        )
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = 0
            var skipped = 0
            var failed = 0
            try {
                candidateIds.forEachIndexed { index, id ->
                    when (repository.deleteOldRevision(id, retainCount)) {
                        RetentionResult.DELETED -> deleted++
                        RetentionResult.SKIPPED -> skipped++
                        RetentionResult.FAILED -> failed++
                    }
                    _uiState.value = _uiState.value.copy(completed = index + 1)
                }
                updateCandidates(repository.getLocalRetentionCandidates(retainCount), candidateIds)
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        context,
                        buildString {
                            append(if (failed == 0) context.getString(R.string.backups_deleted_count, deleted)
                                else context.getString(R.string.cleanup_result, deleted, failed))
                            if (skipped > 0) append(context.getString(R.string.old_backup_cleanup_skipped, skipped))
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                reportError(e)
            } finally {
                _uiState.value = _uiState.value.copy(isDeleting = false)
            }
        }
    }

    private fun reportError(error: Exception) {
        if (error is CancellationException) throw error
        _uiState.value = _uiState.value.copy(
            error = error.localizedMessage ?: error.javaClass.simpleName,
            hasScanned = false, candidates = emptyList(), selectedIds = emptySet(),
        )
    }

    private fun updateCandidates(candidates: List<AppBackupRepository.RetentionCandidate>, selection: Set<String>? = null) {
        _uiState.value = _uiState.value.copy(
            hasScanned = true,
            candidates = candidates,
            selectedIds = candidates.filter { it.removable && (selection == null || it.revision.id in selection) }.mapTo(mutableSetOf()) { it.revision.id },
        )
    }
}
