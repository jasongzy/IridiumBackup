package com.xayah.core.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.xayah.core.data.repository.APP_REFRESH_WORK_NAME
import com.xayah.core.data.repository.APP_SIZE_REFRESH_WORK_NAME
import com.xayah.core.data.repository.FAST_INIT_AND_UPDATE_FILES_WORK_NAME
import com.xayah.core.data.repository.LOAD_APP_BACKUPS_WORK_NAME
import com.xayah.core.data.repository.LOAD_FILE_BACKUPS_WORK_NAME
import com.xayah.core.work.workers.AppsFastInitWorker
import com.xayah.core.work.workers.AppsFastUpdateWorker
import com.xayah.core.work.workers.AppsSizeUpdateWorker
import com.xayah.core.work.workers.AppsInitWorker
import com.xayah.core.work.workers.AppsLoadWorker
import com.xayah.core.work.workers.AppsUpdateWorker
import com.xayah.core.work.workers.FilesLoadWorker
import com.xayah.core.work.workers.FilesUpdateWorker
import java.util.UUID

object WorkManagerInitializer {
    internal val processSessionId: String = UUID.randomUUID().toString()

    /**
     * Fully initialize, update apps
     */
    fun fullInitializeAndUpdateApps(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(APP_REFRESH_WORK_NAME, ExistingWorkPolicy.KEEP, AppsInitWorker.buildRequest())
            .then(AppsUpdateWorker.buildRequest())
            .enqueue()
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps and update newly installed apps
     */
    fun fastInitializeAndUpdateApps(context: Context) {
        WorkManager.getInstance(context)
            .beginUniqueWork(APP_REFRESH_WORK_NAME, ExistingWorkPolicy.KEEP, AppsFastInitWorker.buildRequest())
            .then(AppsFastUpdateWorker.buildRequest())
            .enqueue()
    }

    fun updateAppSizes(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            APP_SIZE_REFRESH_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            AppsSizeUpdateWorker.buildRequest(processSessionId),
        )
    }

    fun cancelAppSizeUpdate(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(APP_SIZE_REFRESH_WORK_NAME)
    }

    fun fastInitializeAndUpdateFiles(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(FAST_INIT_AND_UPDATE_FILES_WORK_NAME, ExistingWorkPolicy.KEEP, FilesUpdateWorker.buildRequest())
    }

    fun loadAppBackups(context: Context, cloudName: String, backupDir: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(LOAD_APP_BACKUPS_WORK_NAME, ExistingWorkPolicy.REPLACE, AppsLoadWorker.buildRequest(cloudName, backupDir))
    }

    fun loadFileBackups(context: Context, cloudName: String, backupDir: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(LOAD_FILE_BACKUPS_WORK_NAME, ExistingWorkPolicy.REPLACE, FilesLoadWorker.buildRequest(cloudName, backupDir))
    }
}
