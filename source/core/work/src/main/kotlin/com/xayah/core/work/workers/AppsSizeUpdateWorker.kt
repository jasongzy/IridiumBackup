package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.INPUT_DATA_KEY_PROCESS_SESSION
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.NotificationProgressThrottle
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R
import com.xayah.core.work.WorkManagerInitializer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsSizeUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val notificationUpdates = NotificationProgressThrottle()
    private val notification = WorkerNotification(this, R.string.calculating_app_total_size, NotificationUtil.APP_SIZE_ID)

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        if (inputData.getString(INPUT_DATA_KEY_PROCESS_SESSION) != WorkManagerInitializer.processSessionId) return@withContext Result.success()
        setForeground(notification.info())
        appsRepo.updateLocalAppSizes { cur, max, content ->
            if (notificationUpdates.shouldUpdate(cur + 1, max)) {
                setForeground(notification.info(content, max, cur + 1))
            }
        }
        Result.success()
    }

    companion object {
        fun buildRequest(sessionId: String) = OneTimeWorkRequestBuilder<AppsSizeUpdateWorker>()
            .setInputData(workDataOf(INPUT_DATA_KEY_PROCESS_SESSION to sessionId))
            .build()
    }
}
