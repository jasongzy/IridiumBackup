package com.xayah.core.work.workers

import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import com.xayah.core.data.repository.WORK_PROGRESS_CURRENT
import com.xayah.core.data.repository.WORK_PROGRESS_MAX
import com.xayah.core.util.NotificationProgressThrottle
import com.xayah.core.util.NotificationUtil
import com.xayah.core.work.R

internal enum class RefreshStage(val start: Int, val title: Int) {
    INITIALIZE(0, R.string.initializing_app_list),
    UPDATE(STAGE_SIZE, R.string.updating_app_list),
}

private const val STAGE_SIZE = 500
private const val TOTAL = 2 * STAGE_SIZE

internal class WorkerNotification(worker: CoroutineWorker, titleRes: Int, private val id: Int = worker.id.hashCode() or Int.MIN_VALUE) {
    private val context = worker.applicationContext
    private val title = ContextCompat.getString(context, titleRes)
    private val builder by lazy { NotificationUtil.getProgressNotificationBuilder(context) }

    fun info(content: String = "", max: Int = 0, current: Int = 0) =
        NotificationUtil.createForegroundInfo(context, builder, title, content, max, current, notificationId = id)
}

internal class AppRefreshProgress(private val worker: CoroutineWorker, private val stage: RefreshStage) {
    private val notification = WorkerNotification(worker, stage.title, NotificationUtil.APP_REFRESH_ID)
    private val throttle = NotificationProgressThrottle()

    fun foregroundInfo(value: Int = stage.start, content: String = "") = notification.info(content, TOTAL, value)

    suspend fun start() = publish(stage.start, "")

    suspend fun update(index: Int, count: Int, content: String) {
        if (throttle.shouldUpdate(index, count)) {
            publish(stage.start + (index.toLong() * STAGE_SIZE / count).toInt(), content)
        }
    }

    suspend fun finish() = publish(stage.start + STAGE_SIZE, "")

    private suspend fun publish(value: Int, content: String) {
        worker.setProgress(workDataOf(WORK_PROGRESS_CURRENT to value, WORK_PROGRESS_MAX to TOTAL))
        worker.setForeground(foregroundInfo(value, content))
    }
}
