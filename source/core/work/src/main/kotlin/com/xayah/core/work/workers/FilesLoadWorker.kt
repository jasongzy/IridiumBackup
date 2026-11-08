package com.xayah.core.work.workers

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.INPUT_DATA_KEY_BACKUP_DIR
import com.xayah.core.data.repository.INPUT_DATA_KEY_CLOUD_NAME
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.NotificationProgressThrottle
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class FilesLoadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val filesRepo: FilesRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val notificationUpdates = NotificationProgressThrottle()
    private val notificationId = id.hashCode() or Int.MIN_VALUE
    private val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(appContext) }
    private var mNotificationInfo: ForegroundInfo? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (mNotificationInfo == null) {
            mNotificationInfo = NotificationUtil.createForegroundInfo(
                appContext,
                mNotificationBuilder,
                ContextCompat.getString(appContext, R.string.loading_backups),
                "",
                notificationId = notificationId,
            )
        }

        return mNotificationInfo!!
    }

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        val cloudName = inputData.getString(INPUT_DATA_KEY_CLOUD_NAME)
        val backupDir = inputData.getString(INPUT_DATA_KEY_BACKUP_DIR) ?: return@withContext Result.failure()
        filesRepo.load(cloudName.orEmpty(), backupDir) { cur, max, content ->
            if (notificationUpdates.shouldUpdate(cur + 1, max)) {
                mNotificationInfo = NotificationUtil.createForegroundInfo(
                    appContext,
                    mNotificationBuilder,
                    ContextCompat.getString(appContext, R.string.loading_backups),
                    content,
                    max,
                    cur + 1,
                    notificationId = notificationId,
                )
                setForeground(mNotificationInfo!!)
            }
        }
        Result.success()
    }

    companion object {
        fun buildRequest(cloudName: String, backupDir: String) = OneTimeWorkRequestBuilder<FilesLoadWorker>()
            .setInputData(
                workDataOf(
                    INPUT_DATA_KEY_CLOUD_NAME to cloudName,
                    INPUT_DATA_KEY_BACKUP_DIR to backupDir,
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}
