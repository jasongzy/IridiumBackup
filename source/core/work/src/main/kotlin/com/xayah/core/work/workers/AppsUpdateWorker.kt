package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.APP_REFRESH_WORK_TAG
import com.xayah.core.data.repository.SettingsDataRepo
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.util.DateUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
    private val settingsDataRepo: SettingsDataRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val progress = AppRefreshProgress(this, RefreshStage.UPDATE)

    override suspend fun getForegroundInfo() = progress.foregroundInfo()

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        progress.start()
        val appsUpdateTime = settingsDataRepo.settingsData.first().appsUpdateTime
        val curTime = DateUtil.getTimestamp()
        val hasPassedOneDay = DateUtil.getNumberOfDaysPassed(appsUpdateTime, curTime) >= 1
        if (hasPassedOneDay) {
            appsRepo.fullUpdate(progress::update)
            settingsDataRepo.setAppsUpdateTime(curTime)
        }
        progress.finish()
        Result.success()
    }

    companion object {
        fun buildRequest() = OneTimeWorkRequestBuilder<AppsUpdateWorker>()
            .addTag(APP_REFRESH_WORK_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}
