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
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.ConstantUtil.DEFAULT_IDLE_TIMEOUT
import com.xayah.core.datastore.readAutoScreenOff
import com.xayah.core.datastore.readScreenOffTimeout
import com.xayah.core.datastore.saveScreenOffCountDown
import com.xayah.core.datastore.saveScreenOffTimeout
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.model.util.set
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.withLog
import com.xayah.core.util.withMainContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal abstract class AbstractProcessingService : Service() {
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

    protected suspend fun TaskEntity.update(
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        rawBytes: Double? = null,
        availableBytes: Double? = null,
        totalBytes: Double? = null,
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
        set(startTimestamp, endTimestamp, rawBytes, availableBytes, totalBytes, totalCount, successCount, failureCount, preprocessingIndex, processingIndex, postProcessingIndex, isProcessing, cloud, backupDir)
        mTaskDao.upsert(this)
    }

    /**
     * To wait for animation.
     */
    protected suspend fun executeAtLeast(
        minTime: Int = 1000,
        finalizer: suspend () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val startTimestamp = DateUtil.getTimestamp()
        try {
            block()
        } finally {
            finalizer()
        }
        val endTimestamp = DateUtil.getTimestamp()
        val diff = endTimestamp - startTimestamp
        if (diff < minTime) {
            delay(minTime - diff)
        }
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
    protected abstract val mTaskRepo: TaskRepository
    protected abstract val mTaskEntity: TaskEntity

    protected val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(mContext, NotificationUtil.Channel.BackupRestore) }
    private val mPreprocessingEntities: MutableList<ProcessingInfoEntity> = mutableListOf()
    private val mPostProcessingEntities: MutableList<ProcessingInfoEntity> = mutableListOf()
    private var mIsInitialized: Boolean = false
    private var cleanupResult: Boolean? = null
    protected var mStartTimestamp: Long = 0
    protected var mEndTimestamp: Long = 0
    protected var requestedCloudName: String? = null
        private set
    protected var requestedBackupDir: String? = null
        private set

    @SuppressLint("StringFormatInvalid")
    suspend fun initialize(cloudName: String? = null, backupDir: String? = null): Long {
        mMutex.withLock {
            if (mIsInitialized.not()) {
                requestedCloudName = cloudName
                requestedBackupDir = backupDir
                mTaskDao.deleteAllTasks()
                mTaskDao.upsert(mTaskEntity).apply {
                    // Initialize task id.
                    mTaskEntity.id = this
                }

                try {
                    onInitializingPreprocessingEntities(mPreprocessingEntities)
                    onInitializingPostProcessingEntities(mPostProcessingEntities)
                    onInitializing()
                } catch (error: Exception) {
                    withContext(NonCancellable) { mTaskDao.deleteTask(mTaskEntity.id) }
                    throw error
                }

                mIsInitialized = true
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

            if (mContext.readAutoScreenOff().first()) {
                mContext.saveScreenOffTimeout(mRootService.getScreenOffTimeout())
                mContext.saveScreenOffCountDown(3)
            }

            mPreprocessingEntities.forEach {
                it.update(state = OperationState.PROCESSING)
                executeAtLeast {
                    onPreprocessing(it)
                }
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
                it.update(state = OperationState.PROCESSING)
                executeAtLeast {
                    onPostProcessing(it)
                }
                mTaskEntity.update(postProcessingIndex = mTaskEntity.postProcessingIndex + 1)
            }

            mEndTimestamp = DateUtil.getTimestamp()
            mTaskEntity.update(endTimestamp = mEndTimestamp, isProcessing = false, processingIndex = mTaskEntity.processingIndex + 1)
            afterPostProcessing()
        }
    }

    suspend fun cleanup(): Boolean = withContext(NonCancellable) {
        cleanupResult ?: runCatchingOnService {
            try {
                clear()
            } finally {
                mContext.saveScreenOffCountDown(0)
                val timeout = mContext.readScreenOffTimeout().first()
                if (timeout != DEFAULT_IDLE_TIMEOUT) {
                    mRootService.setScreenOffTimeout(timeout)
                    mRootService.setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_NORMAL)
                    mContext.saveScreenOffTimeout(DEFAULT_IDLE_TIMEOUT)
                }
            }
        }.also { cleanupResult = it }
    }
}
