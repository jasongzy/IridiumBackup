package com.xayah.core.work.workers

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerParameters
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.work.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@HiltWorker
internal class FilesUpdateWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val filesRepo: FilesRepo,
) : CoroutineWorker(appContext, workerParams) {
    private val notification = WorkerNotification(this, R.string.updating_file_list)

    override suspend fun getForegroundInfo() = notification.info(ContextCompat.getString(appContext, R.string.wait_for_remaining_data_processing))

    override suspend fun doWork(): Result = withContext(defaultDispatcher) {
        setForeground(getForegroundInfo())
        filesRepo.initialize()
        Result.success()
    }

    companion object {
        fun buildRequest() = OneTimeWorkRequestBuilder<FilesUpdateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}
