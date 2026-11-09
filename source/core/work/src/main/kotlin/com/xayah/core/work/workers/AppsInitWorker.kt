package com.xayah.core.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.APP_REFRESH_WORK_TAG
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class AppsInitWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsRepo: AppsRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val progress = AppRefreshProgress(this, RefreshStage.INITIALIZE)

    override suspend fun getForegroundInfo() = progress.foregroundInfo()

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        progress.start()
        appsRepo.fullInitialize(progress::update)
        progress.finish()
        Result.success()
    }

    companion object {
        fun buildRequest() = OneTimeWorkRequestBuilder<AppsInitWorker>()
            .addTag(APP_REFRESH_WORK_TAG)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}
