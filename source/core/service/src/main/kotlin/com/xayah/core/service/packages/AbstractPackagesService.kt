package com.xayah.core.service.packages

import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.DataType
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.set
import com.xayah.core.service.AbstractProcessingService
import com.xayah.core.service.R
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.command.BaseUtil
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

    protected suspend fun killApp(killAppOption: KillAppOption, pkg: TaskDetailPackageEntity, userId: Int = pkg.packageEntity.userId) = run {
        // Kill the package.
        when (killAppOption) {
            KillAppOption.DISABLED -> {
                log { "Won't kill ${pkg.packageEntity.packageName}." }
            }

            KillAppOption.OPTION_I -> {
                log { "Trying to kill ${pkg.packageEntity.packageName}." }
                BaseUtil.killPackage(context = mContext, userId = userId, packageName = pkg.packageEntity.packageName)
            }

            KillAppOption.OPTION_II, KillAppOption.OPTION_III -> {
                log { "Trying to kill ${pkg.packageEntity.packageName}." }
                mRootService.forceStopPackageAsUser(pkg.packageEntity.packageName, userId)
            }
        }
    }

    protected abstract val mPackageDao: PackageDao
    protected abstract val mPackageRepo: PackageRepository
    protected abstract val mRootDir: String
    protected abstract val mAppsDir: String
    protected abstract val mConfigsDir: String
}
