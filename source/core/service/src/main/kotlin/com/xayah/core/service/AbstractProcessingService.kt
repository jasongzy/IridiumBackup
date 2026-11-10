package com.xayah.core.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.SurfaceControlHidden
import androidx.core.content.ContextCompat
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.ConstantUtil.DEFAULT_IDLE_TIMEOUT
import com.xayah.core.datastore.readScreenOffTimeout
import com.xayah.core.datastore.saveScreenOffTimeout
import com.xayah.core.model.OperationState
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.set
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.withLog
import com.xayah.core.util.withMainContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal abstract class AbstractProcessingService : Service() {
    private companion object {
        var clearPreviousTasks = true
    }

    var onServiceDestroyed: (() -> Unit)? = null

    override fun onBind(intent: Intent): IBinder {
        NotificationUtil.cancel(this, NotificationUtil.RESULT_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationUtil.PROCESSING_ID, NotificationUtil.getForegroundNotification(applicationContext), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationUtil.PROCESSING_ID, NotificationUtil.getForegroundNotification(applicationContext))
        }
        return mBinder
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        onServiceDestroyed?.invoke()
    }

    protected suspend fun completeNotification(title: String, content: String) = withMainContext {
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationUtil.notifyResult(mContext, title, content)
    }

    inner class OperationLocalBinder : Binder() {
        fun getService(): AbstractProcessingService = this@AbstractProcessingService
    }

    protected fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { mTAG to msg }
        msg
    }

    protected fun completionSummary(time: String): String =
        "$time, ${mTaskEntity.successCount} ${mContext.getString(R.string.succeed)}" +
            if (mTaskEntity.failureCount > 0) ", ${mTaskEntity.failureCount} ${mContext.getString(R.string.failed)}" else ""

    protected suspend fun runCatchingOnService(block: suspend () -> Unit): Boolean = runCatching { block() }.onFailure {
        if (it is CancellationException) throw it
        mRootService.onFailure(it)
    }.withLog().isSuccess

    protected suspend fun ProcessingInfoEntity.update(
        bytes: Long? = null,
        log: String? = null,
        title: String? = null,
        content: String? = null,
        progress: Float? = null,
        state: OperationState? = null,
    ) {
        set(bytes, log, title, content, progress, state)
        mTaskDao.upsert(this)
    }

    protected suspend fun uploadBackupFile(repo: CloudRepository, client: CloudClient, path: String, destination: String, entity: ProcessingInfoEntity) {
        entity.update(state = OperationState.UPLOADING)
        val result = repo.upload(client, path, destination) { percent -> entity.update(content = "$percent%") }
        entity.update(state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = if (result.isSuccess) null else result.outString)
    }

    protected suspend fun TaskEntity.update(
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        totalCount: Int? = null,
        successCount: Int? = null,
        failureCount: Int? = null,
        preprocessingIndex: Int? = null,
        processingIndex: Int? = null,
        postProcessingIndex: Int? = null,
        isProcessing: Boolean? = null,
        cloud: String? = null,
        backupDir: String? = null,
    ) {
        set(startTimestamp, endTimestamp, totalCount, successCount, failureCount, preprocessingIndex, processingIndex, postProcessingIndex, isProcessing, cloud, backupDir)
        mTaskDao.upsert(this)
    }

    abstract suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>)
    abstract suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>)
    abstract suspend fun onInitializing()
    protected open suspend fun beforePreprocessing() {}
    abstract suspend fun onPreprocessing(entity: ProcessingInfoEntity)
    protected open suspend fun afterPreprocessing() {}
    protected open suspend fun beforePostProcessing() {}
    abstract suspend fun onPostProcessing(entity: ProcessingInfoEntity)
    protected open suspend fun afterPostProcessing() {}
    protected open suspend fun observeProcessingProgress() {}
    abstract suspend fun onProcessing()
    protected open suspend fun clear() {}

    private val mBinder = OperationLocalBinder()
    private val mMutex = Mutex()
    protected val mContext: Context by lazy { ContextCompat.getContextForLanguage(applicationContext) }
    protected abstract val mTAG: String
    protected abstract val mRootService: RemoteRootService
    protected abstract val mPathUtil: PathUtil
    protected abstract val mCommonBackupUtil: CommonBackupUtil
    protected abstract val mTaskDao: TaskDao
    protected abstract val mTaskEntity: TaskEntity

    protected val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(mContext, NotificationUtil.Channel.BackupRestore) }
    private val mPreprocessingEntities: MutableList<ProcessingInfoEntity> = mutableListOf()
    private val mPostProcessingEntities: MutableList<ProcessingInfoEntity> = mutableListOf()
    private var cleanupResult: Boolean? = null
    private val screenMutex = Mutex()
    protected var mStartTimestamp: Long = 0
    protected var mEndTimestamp: Long = 0
    protected var requestedCloudName: String? = null
        private set
    protected var requestedBackupDir: String? = null
        private set
    protected var packageRequest: PackageTaskRequest? = null
        private set

    @SuppressLint("StringFormatInvalid")
    suspend fun initialize(cloudName: String?, backupDir: String?, request: PackageTaskRequest?): Long {
        mMutex.withLock {
            requestedCloudName = cloudName
            requestedBackupDir = backupDir
            packageRequest = request
            if (clearPreviousTasks) {
                mTaskDao.deleteAllTasks()
                clearPreviousTasks = false
            }
            mTaskEntity.id = mTaskDao.upsert(mTaskEntity)

            try {
                onInitializingPreprocessingEntities(mPreprocessingEntities)
                onInitializingPostProcessingEntities(mPostProcessingEntities)
                onInitializing()
            } catch (error: Exception) {
                withContext(NonCancellable) { mTaskDao.deleteTask(mTaskEntity.id) }
                throw error
            }

            return mTaskEntity.id
        }
    }

    suspend fun preprocessing(): Unit = withIOContext {
        mMutex.withLock {
            mStartTimestamp = DateUtil.getTimestamp()
            mTaskEntity.update(startTimestamp = mStartTimestamp)
            log { "Preprocessing is starting." }
            beforePreprocessing()

            mPreprocessingEntities.forEach {
                currentCoroutineContext().ensureActive()
                it.update(state = OperationState.PROCESSING)
                onPreprocessing(it)
                if (it.state == OperationState.ERROR) throw IOException(it.log.ifBlank { it.title })
                mTaskEntity.update(preprocessingIndex = mTaskEntity.preprocessingIndex + 1)
            }

            afterPreprocessing()
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }


    suspend fun processing() = withIOContext {
        mMutex.withLock {
            log { "Processing is starting." }
            coroutineScope {
                val notificationJob = launch { observeProcessingProgress() }
                try {
                    onProcessing()
                } finally {
                    notificationJob.cancelAndJoin()
                }
            }
        }
    }

    suspend fun postProcessing() = withIOContext {
        mMutex.withLock {
            log { "PostProcessing is starting." }
            beforePostProcessing()

            mPostProcessingEntities.forEach {
                currentCoroutineContext().ensureActive()
                it.update(state = OperationState.PROCESSING)
                try {
                    onPostProcessing(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    it.update(state = OperationState.ERROR, progress = 1f, log = e.localizedMessage ?: e.javaClass.simpleName)
                }
                mTaskEntity.update(postProcessingIndex = mTaskEntity.postProcessingIndex + 1)
            }

            mEndTimestamp = DateUtil.getTimestamp()
            val errors = mPostProcessingEntities.filter { it.state == OperationState.ERROR }
            mTaskEntity.update(endTimestamp = mEndTimestamp, isProcessing = false, processingIndex = if (errors.isEmpty()) mTaskEntity.processingIndex + 1 else null)
            if (errors.isNotEmpty()) {
                completeNotification(mContext.getString(R.string.failed), errors.joinToString { it.title })
                throw IOException(errors.joinToString("\n") { if (it.log.isBlank()) it.title else "${it.title}: ${it.log}" })
            }
            afterPostProcessing()
        }
    }

    suspend fun turnOffScreen() = screenMutex.withLock {
        if (cleanupResult != null) return@withLock
        val current = mRootService.getScreenOffTimeout().getOrNull() ?: return@withLock
        if (current == DEFAULT_IDLE_TIMEOUT) return@withLock
        if (current != Int.MAX_VALUE || mContext.readScreenOffTimeout().first() == DEFAULT_IDLE_TIMEOUT) {
            mContext.saveScreenOffTimeout(current)
        }
        if (mRootService.setScreenOffTimeout(Int.MAX_VALUE).isSuccess) {
            mRootService.setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_OFF)
        }
    }

    private suspend fun restoreDisplay() {
        val saved = mContext.readScreenOffTimeout().first()
        if (saved == DEFAULT_IDLE_TIMEOUT) return
        try {
            if (mRootService.getScreenOffTimeout().getOrThrow() == Int.MAX_VALUE) {
                mRootService.setScreenOffTimeout(saved).getOrThrow()
            }
        } finally {
            mRootService.setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_NORMAL).getOrThrow()
        }
        mContext.saveScreenOffTimeout(DEFAULT_IDLE_TIMEOUT)
    }

    protected suspend fun clearCloudWorkspace(path: String, client: CloudClient?) {
        try {
            check(mRootService.deleteRecursively(path)) { "Unable to clear cloud workspace: $path" }
        } finally {
            client?.disconnect()
        }
    }

    suspend fun cleanup(): Boolean = withContext(NonCancellable) {
        screenMutex.withLock {
            cleanupResult ?: runCatchingOnService {
                try {
                    clear()
                } finally {
                    restoreDisplay()
                }
            }.also { cleanupResult = it }
        }
    }
}
