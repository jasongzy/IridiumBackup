package com.xayah.core.service.packages.restore

import android.annotation.SuppressLint
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.readRestorePermissions
import com.xayah.core.datastore.readRestoreSsaid
import com.xayah.core.datastore.readRestoreUser
import com.xayah.core.datastore.saveLastRestoreTime
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.service.R
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import kotlinx.coroutines.flow.first

internal abstract class AbstractRestoreService : AbstractPackagesService() {
    protected abstract val mPackageDao: PackageDao

    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) = Unit

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_remaining_data_processing),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    @SuppressLint("StringFormatInvalid")
    override suspend fun onInitializing() {
        val packages = getPackages()
        packages.forEach { pkg ->
            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_restore, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.restoring), mContext.getString(R.string.preprocessing))
    }

    abstract suspend fun getPackages(): List<PackageEntity>
    abstract suspend fun restore(type: DataType, userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String)

    protected abstract val mPackagesRestoreUtil: PackagesRestoreUtil

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) = Unit

    override suspend fun onProcessing() {
        mTaskEntity.update(rawBytes = mPkgEntities.sumOf { it.packageEntity.selectedDisplayStatsBytes }.toDouble(), availableBytes = mTaskRepo.getAvailableBytes(OpType.RESTORE), totalBytes = mTaskRepo.getTotalBytes(OpType.RESTORE), totalCount = mPkgEntities.size)
        log { "Task count: ${mPkgEntities.size}." }

        val restoreUser = mContext.readRestoreUser().first()
        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        mPkgEntities.forEach { pkg ->
            executeAtLeast {
                log { "Current package: ${pkg.packageEntity}" }

                val p = pkg.packageEntity
                val userId = if (restoreUser == -1) p.userId else restoreUser
                killApp(killAppOption, pkg, userId)

                pkg.update(state = OperationState.PROCESSING)
                val srcDir = "${mAppsDir}/${p.archivesRelativeDir}"
                for (type in DataType.packageArchives) {
                    if (pkg.apkInfo.state == OperationState.ERROR) {
                        pkg.update(dataType = type, state = OperationState.SKIP)
                    } else {
                        restore(type, userId, p, pkg, srcDir)
                    }
                    pkg.update(dataType = type, progress = 1f)
                    pkg.update(processingIndex = pkg.processingIndex + 1)
                }
                if (pkg.isSuccess && p.dataSelectedCount > 0) {
                    if (mContext.readRestorePermissions().first()) mPackagesRestoreUtil.restorePermissions(userId, p)
                    if (mContext.readRestoreSsaid().first()) mPackagesRestoreUtil.restoreSsaid(userId, p)
                }

                if (pkg.isSuccess) {
                    pkg.update(packageEntity = p)
                    mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                } else {
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.restoring),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                mPackageDao.clearActivated(OpType.RESTORE)
                val isSuccess = cleanup()
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR)
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastRestoreTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        completeNotification(mContext.getString(R.string.restore_completed), completionSummary(time))
    }
}
