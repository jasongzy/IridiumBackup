package com.xayah.feature.main.dashboard

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.BackupRequestStore
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.ifEmptyEncodeURLWithSpace
import com.xayah.core.util.navigateSingle
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
    data class RestoreRequest(
        val revision: BackupRevisionEntity,
        val dataStates: PackageDataStates,
    )

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
    private val _damagedRestoreRequest = MutableStateFlow<RestoreRequest?>(null)
    val damagedRestoreRequest = _damagedRestoreRequest.asStateFlow()

    fun startBackup(dataStates: PackageDataStates, navController: NavHostController) {
        val appId = installedApp.value?.id ?: return
        viewModelScope.launch {
            backupRequestStore.prepare(
                ids = listOf(appId),
                dataStates = mapOf(appId to dataStates),
            )
            val route = if (directoryRepository.querySelectedByDirectoryTypeFlow().first() == null) {
                MainRoutes.Directory.route
            } else {
                MainRoutes.PackagesBackupProcessingGraph.route
            }
            navController.navigateSingle(route)
        }
    }

    fun deleteRevision(revision: BackupRevisionEntity) {
        viewModelScope.launch {
            if (repository.deleteRevision(revision)) {
                Toast.makeText(context, R.string.backup_deleted, Toast.LENGTH_SHORT).show()
            }
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
            if (repository.updateRevisionNote(revision, note)) {
                Toast.makeText(context, R.string.note_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startRestore(
        revision: BackupRevisionEntity,
        dataStates: PackageDataStates,
        navController: NavHostController,
        allowDamaged: Boolean = false,
    ) {
        viewModelScope.launch {
            val result = repository.inspectRevision(revision)
            repository.rememberVerification(result)
            if (result.status == BackupVerificationStatus.DAMAGED && !allowDamaged) {
                _damagedRestoreRequest.value = RestoreRequest(revision, dataStates)
                return@launch
            }
            val selection = repository.selectRevisionForRestore(revision, dataStates) ?: return@launch
            navController.navigateSingle(
                MainRoutes.PackagesRestoreProcessingGraph.getRoute(
                    cloudName = selection.cloudName.ifEmptyEncodeURLWithSpace(),
                    backupDir = selection.backupDir.ifEmptyEncodeURLWithSpace(),
                )
            )
        }
    }

    fun dismissDamagedRestoreWarning() {
        _damagedRestoreRequest.value = null
    }
}
