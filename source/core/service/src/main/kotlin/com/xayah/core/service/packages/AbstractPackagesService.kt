package com.xayah.core.service.packages

import com.xayah.core.model.DataType
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.PackageDataStates.Companion.getSelected
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.set
import com.xayah.core.service.AbstractProcessingService
import com.xayah.core.service.R
import com.xayah.core.util.NotificationUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

internal abstract class AbstractPackagesService : AbstractProcessingService() {
    protected val mPkgEntities: MutableList<TaskDetailPackageEntity> = mutableListOf()

    override suspend fun observeProcessingProgress() {
        val title = mContext.getString(if (mTaskEntity.opType == OpType.BACKUP) R.string.backing_up else R.string.restoring)
        mTaskDao.queryProcessingPackageFlow(mTaskEntity.id).filterNotNull().map { current ->
            val info = listOf(current.apkInfo, current.userInfo, current.userDeInfo, current.dataInfo, current.obbInfo, current.mediaInfo)
                .firstOrNull { it.state == OperationState.PROCESSING || it.state == OperationState.UPLOADING || it.state == OperationState.DOWNLOADING }
            val content = current.packageEntity.packageInfo.label + if (info != null) " · ${info.title}" else ""
            content to mPkgEntities.indexOfFirst { it.id == current.id }
        }.distinctUntilChanged().collect { (content, completed) ->
            NotificationUtil.notify(mContext, mNotificationBuilder, title, content, mPkgEntities.size, completed)
        }
    }

    protected suspend fun TaskDetailPackageEntity.update(
        state: OperationState? = null,
        processingIndex: Int? = null,
        packageEntity: PackageEntity? = null,
    ) = run {
        set(state, processingIndex, packageEntity)
        mTaskDao.upsert(this)
    }

    protected suspend fun TaskDetailPackageEntity.update(
        dataType: DataType,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
        progress: Float? = null,
        state: OperationState? = null
    ) = run {
        set(dataType, bytes, log, content, progress, state)
        mTaskDao.upsert(this)
    }

    protected suspend fun preparePackage(option: KillAppOption, task: TaskDetailPackageEntity, userId: Int): Boolean {
        val name = task.packageEntity.packageName
        if (name == mContext.packageName) return true
        return try {
            when (option) {
                KillAppOption.OPTION_I -> mRootService.killPackageProcesses(name, userId)
                KillAppOption.OPTION_II, KillAppOption.OPTION_III -> mRootService.forceStopPackageAsUser(name, userId).getOrThrow()
                KillAppOption.DISABLED -> Unit
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = mContext.getString(R.string.package_stop_failed, name, e.message ?: e.javaClass.simpleName)
            failPackage(task, detail)
            false
        }
    }

    protected suspend fun failPackage(task: TaskDetailPackageEntity, detail: String) {
        val failed = DataType.packageArchives.firstOrNull { it.getSelected(task.packageEntity.dataStates) } ?: DataType.PACKAGE_APK
        DataType.packageArchives.forEach { type ->
            task.set(type, state = if (type == failed) OperationState.ERROR else OperationState.SKIP, log = detail.takeIf { type == failed })
        }
        task.update(state = OperationState.ERROR, processingIndex = DataType.packageArchives.size)
        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1, processingIndex = mTaskEntity.processingIndex + 1)
    }

    protected abstract val mRootDir: String
    protected abstract val mAppsDir: String
}
