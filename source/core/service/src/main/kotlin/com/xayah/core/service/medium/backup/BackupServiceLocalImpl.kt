package com.xayah.core.service.medium.backup

import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.requireBackupStorage
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
import com.xayah.core.util.FileUtil
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
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String) {
        mMediumBackupUtil.backupMedia(m = m, t = t, r = r, dstDir = dstDir)
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    override suspend fun beforePreprocessing() {
        super.beforePreprocessing()
        mRootService.requireBackupStorage(mContext, mRootDir, allowNewDirectory = true)
    }

    override val mRootDir get() = storage.backupDir
    override val mFilesDir by lazy { mPathUtil.getLocalBackupFilesDir(mRootDir) }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir(mRootDir) }

    override suspend fun createDirectory(path: String): Boolean = when {
        path == mFilesDir -> mRootService.mkdirs(path)
        FileUtil.isDescendant(mFilesDir, path) -> mRootService.mkdirs(path)
        else -> false
    }
}
