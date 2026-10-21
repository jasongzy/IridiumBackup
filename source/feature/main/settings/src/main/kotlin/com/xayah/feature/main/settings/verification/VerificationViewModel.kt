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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

val VerificationUiState.validCount: Int
    get() = results.count { it.status == BackupVerificationStatus.VALID }

val VerificationUiState.failedBackups: List<AppBackupRepository.VerificationResult>
    get() = results.filter { it.status != BackupVerificationStatus.VALID }

@HiltViewModel
class VerificationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AppBackupRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VerificationUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(total = repository.getLocalRevisionCount())
        }
    }

    fun verify() {
        if (_uiState.value.total == 0 || _uiState.value.isRunning || _uiState.value.isCleaning) return
        _uiState.value = VerificationUiState(isRunning = true, total = _uiState.value.total)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = repository.verifyAllLocal { completed, total ->
                    _uiState.value = _uiState.value.copy(completed = completed, total = total)
                }
                _uiState.value = _uiState.value.copy(
                    isComplete = true,
                    results = report.results,
                )
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        context,
                        if (report.results.all { it.status == BackupVerificationStatus.VALID }) {
                            context.getString(R.string.verification_valid_count, report.results.size)
                        } else {
                            context.getString(
                                R.string.verification_finished,
                                report.results.count { it.status == BackupVerificationStatus.VALID },
                                report.results.count { it.status != BackupVerificationStatus.VALID },
                            )
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                _uiState.value = _uiState.value.copy(isRunning = false)
            }
        }
    }

    fun cleanFailedBackups() {
        val current = _uiState.value
        if (current.isRunning || current.isCleaning || current.failedBackups.isEmpty()) return
        _uiState.value = current.copy(
            isCleaning = true,
            cleanupCompleted = 0,
            cleanupTotal = current.failedBackups.size,
            cleanupFailedCount = 0,
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = repository.deleteFailedLocalBackups(current.results) { completed, total ->
                    _uiState.value = _uiState.value.copy(cleanupCompleted = completed, cleanupTotal = total)
                }
                _uiState.value = _uiState.value.copy(
                    results = _uiState.value.results.filterNot { it.revision.id in report.deletedRevisionIds },
                    total = repository.getLocalRevisionCount(),
                    cleanupFailedCount = report.failedCount,
                )
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        context,
                        if (report.failedCount == 0) {
                            context.getString(R.string.backups_deleted_count, report.deletedRevisionIds.size)
                        } else {
                            context.getString(R.string.cleanup_result, report.deletedRevisionIds.size, report.failedCount)
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                _uiState.value = _uiState.value.copy(isCleaning = false)
            }
        }
    }
}
