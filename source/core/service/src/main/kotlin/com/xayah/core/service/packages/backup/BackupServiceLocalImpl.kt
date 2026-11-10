package com.xayah.core.service.packages.backup

import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.requireBackupStorage
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class BackupServiceLocalImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceLocalImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    @Inject
    override lateinit var mAppBackupRepository: AppBackupRepository


    override suspend fun beforePreprocessing() {
        super.beforePreprocessing()
        mRootService.requireBackupStorage(mContext, mRootDir, allowNewDirectory = true)
    }

    override val mRootDir get() = storage.backupDir
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir(mRootDir) }
    override val mConfigsDir by lazy { "$mRootDir/${PathUtil.getConfigsRelativeDir()}" }
}
