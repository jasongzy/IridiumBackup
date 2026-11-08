package com.xayah.core.work.workers

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.APP_REFRESH_WORK_TAG
import com.xayah.core.data.repository.SettingsDataRepo
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationProgressThrottle
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsUpdateWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
    private val settingsDataRepo: SettingsDataRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val notificationUpdates = NotificationProgressThrottle()
    private val mNotificationBuilder by lazy { NotificationUtil.getProgressNotificationBuilder(appContext) }
    private var mNotificationInfo: ForegroundInfo? = null

    override suspend fun getForegroundInfo(): ForegroundInfo {
        if (mNotificationInfo == null) {
            mNotificationInfo = NotificationUtil.createForegroundInfo(
                appContext,
                mNotificationBuilder,
                ContextCompat.getString(appContext, R.string.updating_app_list),
                ""
            )
        }

        return mNotificationInfo!!
    }

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        val appsUpdateTime = settingsDataRepo.settingsData.first().appsUpdateTime
        val curTime = DateUtil.getTimestamp()
        val hasPassedOneDay = DateUtil.getNumberOfDaysPassed(appsUpdateTime, curTime) >= 1
        if (hasPassedOneDay) {
            appsRepo.fullUpdate { cur, max, content ->
                if (notificationUpdates.shouldUpdate(cur + 1, max)) {
                    val progress = publishProgress(cur + 1, max, APP_UPDATE_STAGE, APP_REFRESH_STAGE_COUNT)
                    mNotificationInfo = NotificationUtil.createForegroundInfo(
                        appContext,
                        mNotificationBuilder,
                        ContextCompat.getString(appContext, R.string.updating_app_list),
                        content,
                        progress.max,
                        progress.current,
                    )
                    setForeground(mNotificationInfo!!)
                }
            }
            settingsDataRepo.setAppsUpdateTime(curTime)
        }
        publishProgress(1, 1, APP_UPDATE_STAGE, APP_REFRESH_STAGE_COUNT)
        Result.success()
    }

    companion object {
        fun buildRequest() = OneTimeWorkRequestBuilder<AppsUpdateWorker>()
            .addTag(APP_REFRESH_WORK_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}
