package com.xayah.feature.main.dashboard

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.PackageRequestStore
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.ui.route.MainRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppRevisionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val repository: AppBackupRepository,
    private val appsRepo: AppsRepo,
    private val requestStore: PackageRequestStore,
    private val directoryRepository: DirectoryRepository,
) : ViewModel() {
    private val packageName = checkNotNull(savedStateHandle.get<String>(MainRoutes.ARG_PACKAGE_NAME))
    private val userId = checkNotNull(savedStateHandle.get<String>(MainRoutes.ARG_USER_ID)).toInt()

    val app = repository.observeApp(packageName, userId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val revisions = repository.observeRevisions(packageName, userId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val installedApp = appsRepo.getApp(packageName, userId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val verificationResults = repository.verificationResults
    data class VerificationProgress(val file: String, val bytes: Long, val total: Long)
    private val _verificationProgress = MutableStateFlow<Map<String, VerificationProgress?>>(emptyMap())
    val verificationProgress = _verificationProgress.asStateFlow()
    private val verificationJobs = mutableMapOf<String, Job>()
    enum class NoteSaveState { SAVING, SAVED }
    private val _noteSaveStates = MutableStateFlow<Map<String, NoteSaveState>>(emptyMap())
    val noteSaveStates = _noteSaveStates.asStateFlow()

    suspend fun prepareBackup(appId: Long, dataStates: PackageDataStates): String? {
        val hasDirectory = directoryRepository.querySelectedByDirectoryTypeFlow().first() != null
        if (!requestStore.prepareBackup(listOf(appId), mapOf(appId to dataStates))) {
            Toast.makeText(context, R.string.backup_app_unavailable, Toast.LENGTH_SHORT).show()
            return null
        }
        return if (hasDirectory) MainRoutes.PackagesBackupProcessingGraph.route else MainRoutes.Directory.route
    }

    fun deleteRevision(revision: BackupRevisionEntity) {
        viewModelScope.launch {
            val message = if (repository.deleteRevision(revision)) R.string.backup_deleted else R.string.backup_delete_failed
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun inspect(revision: BackupRevisionEntity, onProgress: suspend (String, Long, Long) -> Unit = { _, _, _ -> }) = try {
        repository.inspectRevision(revision, onProgress)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Toast.makeText(context, e.localizedMessage ?: context.getString(com.xayah.core.data.R.string.verification_unreadable), Toast.LENGTH_LONG).show()
        null
    }

    fun verifyRevision(revision: BackupRevisionEntity) {
        if (revision.id in verificationJobs) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _verificationProgress.update { it + (revision.id to null) }
            try {
                val result = inspect(revision) { file, bytes, total ->
                    _verificationProgress.update { it + (revision.id to VerificationProgress(file, bytes, total)) }
                } ?: return@launch
                Toast.makeText(
                    context,
                    when (result.status) {
                        BackupVerificationStatus.NOT_VERIFIED -> R.string.not_verified
                        BackupVerificationStatus.VALID -> R.string.backup_valid
                        BackupVerificationStatus.DAMAGED -> R.string.backup_damaged
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                _verificationProgress.update { it - revision.id }
                verificationJobs.remove(revision.id)
            }
        }
        verificationJobs[revision.id] = job
        job.start()
    }

    fun cancelVerification(revision: BackupRevisionEntity) {
        verificationJobs[revision.id]?.cancel()
    }

    fun dismissSavedNote(id: String) {
        if (_noteSaveStates.value[id] == NoteSaveState.SAVED) _noteSaveStates.value -= id
    }

    fun updateRevisionNote(revision: BackupRevisionEntity, note: String) {
        if (revision.id in _noteSaveStates.value) return
        _noteSaveStates.value += revision.id to NoteSaveState.SAVING
        viewModelScope.launch {
            try {
                val saved = repository.updateRevisionNote(revision, note)
                Toast.makeText(context, if (saved) R.string.note_saved else R.string.backup_note_save_failed, Toast.LENGTH_SHORT).show()
                if (saved) _noteSaveStates.value += revision.id to NoteSaveState.SAVED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "${context.getString(R.string.backup_note_save_failed)}\n${e.localizedMessage.orEmpty()}", Toast.LENGTH_LONG).show()
            } finally {
                if (_noteSaveStates.value[revision.id] == NoteSaveState.SAVING) _noteSaveStates.value -= revision.id
            }
        }
    }

    suspend fun prepareRestore(revision: BackupRevisionEntity, dataStates: PackageDataStates): String? {
        if (revision.repositoryId.startsWith(':') && inspect(revision) == null) return null
        val app = repository.revisionForRestore(revision, dataStates) ?: run {
            Toast.makeText(context, R.string.restore_revision_unavailable, Toast.LENGTH_SHORT).show()
            return null
        }
        requestStore.prepareRestore(listOf(app))
        return MainRoutes.PackagesRestoreProcessingGraph.getRoute(
            cloudName = app.indexInfo.cloud,
            backupDir = app.indexInfo.backupDir,
        )
    }
}
