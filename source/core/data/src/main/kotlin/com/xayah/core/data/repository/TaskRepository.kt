package com.xayah.core.data.repository

import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.ProcessingType
import javax.inject.Inject

class TaskRepository @Inject constructor(
    private val taskDao: TaskDao,
) {
    fun queryTaskFlow(id: Long) = taskDao.queryTaskFlow(id)
    fun queryTasksFlow() = taskDao.queryTasksFlow()

    fun queryProcessingInfoFlow(taskId: Long, type: ProcessingType) = taskDao.queryProcessingInfoFlow(taskId, type)
    fun queryProcessingInfoFlow(taskId: Long) = taskDao.queryProcessingInfoFlow(taskId)
    fun queryPackageFlow(taskId: Long) = taskDao.queryPackageFlow(taskId)
    fun queryMediaFlow(taskId: Long) = taskDao.queryMediaFlow(taskId)
    suspend fun deleteTask(id: Long) = taskDao.deleteTask(id)
}
