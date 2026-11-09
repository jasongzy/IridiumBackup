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
import com.xayah.core.data.repository.BackupRequestStore
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.ifEmptyEncodeURLWithSpace
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppRevisionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val repository: AppBackupRepository,
    private val appsRepo: AppsRepo,
    private val backupRequestStore: BackupRequestStore,
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
    private val _verifyingRevisionIds = MutableStateFlow<Set<String>>(emptySet())
    val verifyingRevisionIds = _verifyingRevisionIds.asStateFlow()

    suspend fun prepareBackup(appId: Long, dataStates: PackageDataStates): String? {
        val hasDirectory = directoryRepository.querySelectedByDirectoryTypeFlow().first() != null
        if (!backupRequestStore.prepare(listOf(appId), mapOf(appId to dataStates))) {
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

    fun verifyRevision(revision: BackupRevisionEntity) {
        if (revision.id in _verifyingRevisionIds.value) return
        viewModelScope.launch {
            _verifyingRevisionIds.value += revision.id
            try {
                val result = repository.inspectRevision(revision)
                repository.rememberVerification(result)
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
                _verifyingRevisionIds.value -= revision.id
            }
        }
    }

    fun updateRevisionNote(revision: BackupRevisionEntity, note: String) {
        viewModelScope.launch {
            val message = if (repository.updateRevisionNote(revision, note)) R.string.note_saved else R.string.backup_note_save_failed
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun prepareRestore(revision: BackupRevisionEntity, dataStates: PackageDataStates): String? {
        if (revision.repositoryId.startsWith(':')) repository.rememberVerification(repository.inspectRevision(revision))
        val selection = repository.selectRevisionForRestore(revision, dataStates) ?: run {
            Toast.makeText(context, R.string.restore_revision_unavailable, Toast.LENGTH_SHORT).show()
            return null
        }
        return MainRoutes.PackagesRestoreProcessingGraph.getRoute(
            cloudName = selection.cloudName.ifEmptyEncodeURLWithSpace(),
            backupDir = selection.backupDir.ifEmptyEncodeURLWithSpace(),
        )
    }
}
