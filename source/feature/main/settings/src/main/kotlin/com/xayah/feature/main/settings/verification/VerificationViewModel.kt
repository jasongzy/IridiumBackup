package com.xayah.feature.main.settings.verification

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.feature.main.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VerificationUiState(
    val isRunning: Boolean = false,
    val isComplete: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val results: List<AppBackupRepository.VerificationResult> = emptyList(),
    val isCleaning: Boolean = false,
    val cleanupCompleted: Int = 0,
    val cleanupTotal: Int = 0,
    val cleanupFailedCount: Int = 0,
    val error: String? = null,
)

@HiltViewModel
class VerificationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppBackupRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VerificationUiState())
    val uiState = _uiState.asStateFlow()

    val availableCount = repository.observeLocalRevisionCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun verify() {
        if (availableCount.value == 0 || _uiState.value.isRunning || _uiState.value.isCleaning) return
        _uiState.value = VerificationUiState(isRunning = true, total = availableCount.value)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = repository.verifyAllLocal { completed, total ->
                    _uiState.value = _uiState.value.copy(completed = completed, total = total)
                }
                _uiState.value = _uiState.value.copy(
                    isComplete = true,
                    results = results,
                )
                val validCount = results.count { it.status == BackupVerificationStatus.VALID }
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        context,
                        if (validCount == results.size) {
                            context.getString(R.string.verification_valid_count, results.size)
                        } else {
                            context.getString(
                                R.string.verification_finished,
                                validCount, results.size - validCount,
                            )
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                reportError(e)
            } finally {
                _uiState.value = _uiState.value.copy(isRunning = false)
            }
        }
    }

    fun cleanFailedBackups() {
        val current = _uiState.value
        if (current.isRunning || current.isCleaning || current.results.none { it.canClean }) return
        _uiState.value = current.copy(
            isCleaning = true,
            cleanupCompleted = 0,
            cleanupTotal = current.results.count { it.canClean },
            cleanupFailedCount = 0,
            error = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = repository.deleteFailedLocalBackups(current.results) { completed, total ->
                    _uiState.value = _uiState.value.copy(cleanupCompleted = completed, cleanupTotal = total)
                }
                _uiState.value = _uiState.value.copy(
                    results = report.results,
                    cleanupFailedCount = report.failedCount,
                )
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        context,
                        if (report.failedCount == 0) {
                            context.getString(R.string.backups_deleted_count, report.deletedCount)
                        } else {
                            context.getString(R.string.cleanup_result, report.deletedCount, report.failedCount)
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                reportError(e)
            } finally {
                _uiState.value = _uiState.value.copy(isCleaning = false)
            }
        }
    }

    private fun reportError(error: Exception) {
        if (error is CancellationException) throw error
        _uiState.value = _uiState.value.copy(error = error.localizedMessage ?: context.getString(com.xayah.core.data.R.string.verification_unreadable))
    }
}
