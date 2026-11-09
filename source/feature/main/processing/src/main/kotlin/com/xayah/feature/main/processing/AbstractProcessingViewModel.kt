package com.xayah.feature.main.processing

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readScreenOffCountDown
import com.xayah.core.model.OperationState
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.AbstractProcessingServiceProxy
import com.xayah.core.ui.model.ProcessingCardItem
import com.xayah.core.ui.model.ProcessingDataCardItem
import com.xayah.core.ui.util.addInfo
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import com.xayah.core.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

data class IndexUiState(
    val state: OperationState,
    val storageIndex: Int,
    val storageType: StorageMode,
    val cloudEntity: CloudEntity?,
    val backupDir: String? = null,
    val failure: String? = null,
) : UiState

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
        storageIndex = 0,
        storageType = StorageMode.Local,
        cloudEntity = null
    )
) {
    enum class ExitState { IDLE, WAITING, COMPLETE }

    private val _exitState = MutableStateFlow(ExitState.IDLE)
    val exitState: StateFlow<ExitState> = _exitState
    private val processingJob = AtomicReference<Job?>()
    private var initializationJob: Job? = null
    protected var confirmedRequest: PackageTaskRequest? = null

    fun initialize(autoStart: Boolean) {
        if (initializationJob != null || _exitState.value != ExitState.IDLE) return
        initializationJob = launchOnIO {
            val state = uiState.value
            val service = if (state.storageType == StorageMode.Cloud) mCloudService else mLocalService
            try {
                val restoreSource = (confirmedRequest as? PackageTaskRequest.Restore)?.packages?.firstOrNull()?.indexInfo
                _taskId.value = service.initialize(
                    restoreSource?.cloud ?: state.cloudEntity?.name,
                    restoreSource?.backupDir ?: state.backupDir,
                    confirmedRequest,
                )
            } catch (error: Exception) {
                releaseService(service)
                if (error is CancellationException) throw error
                fail(error)
                return@launchOnIO
            }
            if (autoStart) emitIntent(ProcessingUiIntent.Process)
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
        LogUtil.log { "Processing" to error.stackTraceToString() }
        emitState(uiState.value.copy(state = OperationState.ERROR, failure = error.localizedMessage ?: error.javaClass.simpleName))
    }

    open suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {}

    init {
        val onRootFailure: (Throwable) -> Unit = {
            val msg = it.message
            if (msg != null)
                emitEffectOnIO(IndexUiEffect.ShowSnackbar(message = msg))
        }
        mRootService.onFailure = onRootFailure
        addCloseable(AutoCloseable {
            if (mRootService.onFailure === onRootFailure) mRootService.onFailure = {}
        })
    }

    override suspend fun onEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is ProcessingUiIntent.Process -> {
                if (state.state != OperationState.IDLE || _taskId.value < 0 || _exitState.value != ExitState.IDLE) return
                val job = currentCoroutineContext().job
                if (!processingJob.compareAndSet(null, job)) return
                emitState(state.copy(state = OperationState.PROCESSING))
                val service = if (state.storageType == StorageMode.Cloud) mCloudService else mLocalService
                try {
                    try {
                        service.preprocessing()
                        service.processing()
                        service.postProcessing()
                    } finally {
                        releaseService(service)
                    }
                    emitState(uiState.value.copy(state = OperationState.DONE))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(error)
                } finally {
                    processingJob.compareAndSet(job, null)
                }
            }

            is ProcessingUiIntent.Exit -> {
                if (!_exitState.compareAndSet(ExitState.IDLE, ExitState.WAITING)) return
                try {
                    initializationJob?.cancel()
                    processingJob.get()?.cancelAndJoin()
                    initializationJob?.join()
                    withMainContext {
                        if (state.storageType == StorageMode.Cloud) mCloudService.destroyService(true)
                        else mLocalService.destroyService(true)
                    }
                    val id = _taskId.value
                    if (id >= 0) mTaskRepo.deleteTask(id)
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
                    val service = if (state.storageType == StorageMode.Cloud) mCloudService else mLocalService
                    service.turnOffScreen()
                }
            }

            else -> {
                onOtherEvent(state, intent)
            }
        }
    }

    protected abstract val _dataItems: Flow<List<ProcessingDataCardItem>>

    protected val _taskId: MutableStateFlow<Long> = MutableStateFlow(-1)
    private var _task: Flow<TaskEntity?> = _taskId.flatMapLatest { id -> mTaskRepo.queryTaskFlow(id).flowOnIO() }
    private val _preItemsProgress: MutableStateFlow<Float> = MutableStateFlow(0F)
    private var _preItems: Flow<List<ProcessingCardItem>> = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, ProcessingType.PREPROCESSING)
            .map { infoList ->
                _preItemsProgress.value = infoList.sumOf { it.progress.toDouble() }.toFloat() / infoList.size.coerceAtLeast(1)
                val items = mutableListOf<ProcessingCardItem>()
                infoList.forEach {
                    items.addInfo(it)
                }
                items
            }
            .flowOnIO()
    }
    private val _postItemsProgress: MutableStateFlow<Float> = MutableStateFlow(0F)
    private val _postItems: Flow<List<ProcessingCardItem>> = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, ProcessingType.POST_PROCESSING)
            .map { infoList ->
                _postItemsProgress.value = infoList.sumOf { it.progress.toDouble() }.toFloat() / infoList.size.coerceAtLeast(1)
                val items = mutableListOf<ProcessingCardItem>()
                infoList.forEach {
                    items.addInfo(it)
                }
                items
            }
            .flowOnIO()
    }
    private val _screenOffCountDown = mContext.readScreenOffCountDown().flowOnIO()

    val task: StateFlow<TaskEntity?> = _task.stateInScope(null)
    val preItemsProgress: StateFlow<Float> = _preItemsProgress.stateInScope(0F)
    val preItems: StateFlow<List<ProcessingCardItem>> = _preItems.stateInScope(listOf())
    val dataItems: StateFlow<List<ProcessingDataCardItem>> by lazy { _dataItems.stateInScope(listOf()) }
    val postItemsProgress: StateFlow<Float> = _postItemsProgress.stateInScope(0F)
    val postItems: StateFlow<List<ProcessingCardItem>> = _postItems.stateInScope(listOf())
    val screenOffCountDown: StateFlow<Int> = _screenOffCountDown.stateInScope(0)
}
