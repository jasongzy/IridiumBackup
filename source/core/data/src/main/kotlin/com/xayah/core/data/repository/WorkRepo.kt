package com.xayah.core.data.repository

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class WorkRepo @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val appRefreshWorkInfos
        get() = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(APP_REFRESH_WORK_NAME)

    fun isAppRefreshRunning() = appRefreshWorkInfos.map { workInfos ->
        workInfos.any { APP_REFRESH_WORK_TAG in it.tags && !it.state.isFinished }
    }

    private fun isWorkRunning(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name)
        .map { jobs -> jobs.any { !it.state.isFinished } }

    fun getAppRefreshProgress() = appRefreshWorkInfos.map { workInfos ->
        val work = workInfos.firstOrNull {
            APP_REFRESH_WORK_TAG in it.tags && it.state == androidx.work.WorkInfo.State.RUNNING
        } ?: return@map null
        val max = work.progress.getInt(WORK_PROGRESS_MAX, 0)
        if (max > 0) {
            work.progress.getInt(WORK_PROGRESS_CURRENT, 0).toFloat().div(max).coerceIn(0f, 1f)
        } else {
            null
        }
    }

    fun isLoadAppBackupsRunning() = isWorkRunning(LOAD_APP_BACKUPS_WORK_NAME)

    fun isFastInitAndUpdateFilesRunning() = isWorkRunning(FAST_INIT_AND_UPDATE_FILES_WORK_NAME)

    fun isLoadFileBackupsRunning() = isWorkRunning(LOAD_FILE_BACKUPS_WORK_NAME)
}

const val APP_REFRESH_WORK_NAME = "DbAppRefreshWork"
const val APP_REFRESH_WORK_TAG = "DbAppRefresh"
const val APP_SIZE_REFRESH_WORK_NAME = "DbAppSizeRefreshWork"
const val FAST_INIT_AND_UPDATE_FILES_WORK_NAME = "DbFastInitAndUpdateFilesWork"
const val LOAD_APP_BACKUPS_WORK_NAME = "DbLoadAppBackupsWork"
const val LOAD_FILE_BACKUPS_WORK_NAME = "DbLoadFileBackupsWork"

const val INPUT_DATA_KEY_PROCESS_SESSION = "InputDataKeyProcessSession"
const val INPUT_DATA_KEY_CLOUD_NAME = "InputDataKeyCloudName"
const val WORK_PROGRESS_CURRENT = "WorkProgressCurrent"
const val WORK_PROGRESS_MAX = "WorkProgressMax"
