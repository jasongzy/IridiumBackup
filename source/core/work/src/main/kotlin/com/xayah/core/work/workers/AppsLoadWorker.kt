package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.INPUT_DATA_KEY_BACKUP_DIR
import com.xayah.core.data.repository.INPUT_DATA_KEY_CLOUD_NAME
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.NotificationProgressThrottle
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsLoadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val notificationUpdates = NotificationProgressThrottle()
    private val notification = WorkerNotification(this, R.string.loading_backups)

    override suspend fun getForegroundInfo() = notification.info()

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        val cloudName = inputData.getString(INPUT_DATA_KEY_CLOUD_NAME)
        val backupDir = inputData.getString(INPUT_DATA_KEY_BACKUP_DIR) ?: return@withContext Result.failure()
        setForeground(getForegroundInfo())
        appsRepo.load(cloudName.orEmpty(), backupDir) { cur, max, content ->
            if (notificationUpdates.shouldUpdate(cur + 1, max)) {
                setForeground(notification.info(content, max, cur + 1))
            }
        }
        Result.success()
    }

    companion object {
        fun buildRequest(cloudName: String, backupDir: String) = OneTimeWorkRequestBuilder<AppsLoadWorker>()
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
