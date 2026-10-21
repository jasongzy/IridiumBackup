package com.xayah.core.service.packages.backup

import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.BackupRequestStore
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
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

    @Inject
    override lateinit var mTaskRepo: TaskRepository

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

    override suspend fun backup(type: DataType, p: PackageEntity, previous: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        if (type == DataType.PACKAGE_APK) {
            val previousArchive = previous?.let {
                mPackageRepo.getArchiveDst("$mAppsDir/${it.archivesRelativeDir}", type, it.indexInfo.compressionType)
            }
            mPackagesBackupUtil.backupApk(p, previousArchive, t, dstDir)
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, dataType = type, dstDir = dstDir)
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    @Inject
    override lateinit var mAppBackupRepository: AppBackupRepository

    @Inject
    override lateinit var mBackupRequestStore: BackupRequestStore

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }

    override suspend fun createDirectory(path: String): Boolean = when {
        path == mAppsDir || path == mConfigsDir -> mRootService.mkdirs(path)
        FileUtil.isDescendant(mAppsDir, path) -> mRootService.mkdirs(path)
        else -> false
    }
}
