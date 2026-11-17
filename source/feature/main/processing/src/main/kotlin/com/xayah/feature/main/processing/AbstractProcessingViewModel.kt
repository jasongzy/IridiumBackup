package com.xayah.feature.main.processing

import android.content.Context
import android.os.SystemClock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation.NavController
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readAutoScreenOff
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.model.OperationState
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.TaskStorage
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.AbstractProcessingServiceProxy
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.ProcessingDataCardItem
import com.xayah.core.ui.util.toProcessingCardItem
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class IndexUiState(
    val state: OperationState,
    val storageType: StorageMode,
    val cloudEntity: CloudEntity?,
    val backupDir: String? = null,
    val failure: String? = null,
    val screenOffAt: Long? = null,
    val autoSelectFirstCloud: Boolean = true,
) : UiState

data object LoadRestoreSource : ProcessingUiIntent()
data class FinishSetup(val navController: NavController) : ProcessingUiIntent()

open class ProcessingUiIntent : UiIntent {
    data object Process : ProcessingUiIntent()
    data object Exit : ProcessingUiIntent()
    data object TurnOffScreen : ProcessingUiIntent()
}

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
abstract class AbstractProcessingViewModel(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    private val mTaskRepo: TaskRepository,
    private val mLocalService: AbstractProcessingServiceProxy,
    private val mCloudService: AbstractProcessingServiceProxy,
) : BaseViewModel<IndexUiState, ProcessingUiIntent, IndexUiEffect>(
    IndexUiState(
        state = OperationState.IDLE,
        storageType = StorageMode.Local,
        cloudEntity = null
    )
) {
    enum class ExitState { IDLE, WAITING, COMPLETE }

    private val _exitState = MutableStateFlow(ExitState.IDLE)
    val exitState: StateFlow<ExitState> = _exitState
    private val startRequested = CompletableDeferred<Unit>()
    private var taskJob: Job? = null
    protected var confirmedRequest: PackageTaskRequest? = null

    private var confirmedStorage: TaskStorage? = null

    fun selectCloudAccount(account: CloudEntity?) {
        if (!isStarting.value) updateState { it.copy(cloudEntity = account, autoSelectFirstCloud = false) }
    }

    fun selectStorage(index: Int) {
        if (!isStarting.value) updateState { it.copy(storageType = StorageMode.entries[index]) }
    }

    private val _isStarting = MutableStateFlow(false)
    val isStarting = _isStarting.asStateFlow()

    protected suspend fun finishSetup(state: IndexUiState, onReady: suspend () -> Unit) {
        if (!_isStarting.compareAndSet(false, true)) return
        val progress = if (state.storageType == StorageMode.Cloud) {
            emitEffectOnIO(
                IndexUiEffect.ShowSnackbar(
                    type = SnackbarType.Loading,
                    message = mContext.getString(R.string.processing),
                    duration = SnackbarDuration.Indefinite,
                )
            )
        } else null
        try {
            val source = (confirmedRequest as? PackageTaskRequest.Restore)?.packages?.firstOrNull()?.indexInfo
            val account = if (state.storageType == StorageMode.Cloud) {
                checkNotNull(state.cloudEntity) { mContext.getString(R.string.choose_an_account) }.also { it.getCloud().testConnection() }
            } else null
            confirmedStorage = TaskStorage(
                cloud = source?.cloud ?: account?.name.orEmpty(),
                backupDir = source?.backupDir ?: state.backupDir ?: account?.remote ?: mContext.readBackupSavePath().first(),
            )
            withMainContext { onReady() }
        } catch (error: Exception) {
            confirmedStorage = null
            _isStarting.value = false
            if (error is CancellationException) throw error
            emitEffectOnIO(
                IndexUiEffect.ShowSnackbar(
                    type = SnackbarType.Error,
                    message = error.localizedMessage ?: error.javaClass.simpleName,
                    duration = SnackbarDuration.Long,
                )
            )
        } finally {
            progress?.cancel()
        }
    }

    fun initialize(autoStart: Boolean) {
        if (taskJob != null || _exitState.value != ExitState.IDLE) return
        taskJob = launchOnIO {
            try {
                runTask(autoStart)
                // Keep the result available until its screen closes.
                if (_taskId.value >= 0) awaitCancellation()
            } finally {
                val id = _taskId.value
                if (id >= 0) withContext(NonCancellable) { mTaskRepo.deleteTask(id) }
            }
        }
    }

    private suspend fun runTask(autoStart: Boolean) {
        val storage = confirmedStorage ?: return fail(IllegalStateException(mContext.getString(com.xayah.core.service.R.string.package_request_expired)))
        val service = if (storage.cloud.isEmpty()) mLocalService else mCloudService
        try {
            coroutineScope {
                try {
                    _taskId.value = service.initialize(storage, confirmedRequest)
                    if (!autoStart) startRequested.await()
                    val screenOffAt = if (mContext.readAutoScreenOff().first()) SystemClock.elapsedRealtime() + 3_000 else null
                    emitState(uiState.value.copy(state = OperationState.PROCESSING, screenOffAt = screenOffAt))
                    service.preprocessing()
                    service.processing()
                    service.postProcessing()
                } finally {
                    releaseService(service)
                }
            }
            emitState(uiState.value.copy(state = OperationState.DONE))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            fail(e)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private suspend fun releaseService(service: AbstractProcessingServiceProxy) = withContext(NonCancellable) {
        try {
            service.cleanup()
        } finally {
            withMainContext { service.destroyService() }
        }
    }

    private suspend fun fail(error: Exception) {
        LogUtil.log("Processing", error.stackTraceToString())
        emitState(uiState.value.copy(state = OperationState.ERROR, failure = error.localizedMessage ?: error.javaClass.simpleName))
    }

    open suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {}

    init {
        addCloseable(mRootService.observeFailures { error ->
            error.message?.let { emitEffectOnIO(IndexUiEffect.ShowSnackbar(message = it)) }
        })
    }

    override suspend fun onEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is ProcessingUiIntent.Process -> {
                if (state.state == OperationState.IDLE && _taskId.value >= 0 && _exitState.value == ExitState.IDLE) startRequested.complete(Unit)
            }

            is ProcessingUiIntent.Exit -> {
                if (!_exitState.compareAndSet(ExitState.IDLE, ExitState.WAITING)) return
                try {
                    taskJob?.cancelAndJoin()
                    _exitState.value = ExitState.COMPLETE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _exitState.value = ExitState.IDLE
                    fail(e)
                }
            }

            is ProcessingUiIntent.TurnOffScreen -> {
                if (uiState.value.state == OperationState.PROCESSING && _exitState.value == ExitState.IDLE) {
                    val service = if (confirmedStorage?.cloud.isNullOrEmpty()) mLocalService else mCloudService
                    withContext(Dispatchers.IO) { service.turnOffScreen() }
                }
            }

            else -> {
                onOtherEvent(state, intent)
            }
        }
    }

    protected abstract val _dataItems: Flow<List<ProcessingDataCardItem>>

    protected val _taskId: MutableStateFlow<Long> = MutableStateFlow(-1)
    val task = _taskId.flatMapLatest { mTaskRepo.queryTaskFlow(it) }.stateInScope(null)
    val preItems = processingItems(ProcessingType.PREPROCESSING)
    val postItems = processingItems(ProcessingType.POST_PROCESSING)
    val dataItems: StateFlow<List<ProcessingDataCardItem>> by lazy { _dataItems.stateInScope(emptyList()) }

    private fun processingItems(type: ProcessingType) = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, type).map { rows -> rows.map { it.toProcessingCardItem } }
    }.flowOnIO().stateInScope(emptyList())
}
