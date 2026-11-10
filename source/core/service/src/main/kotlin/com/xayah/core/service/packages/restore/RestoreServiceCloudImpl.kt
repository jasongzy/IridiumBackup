package com.xayah.core.service.packages.restore

import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class RestoreServiceCloudImpl @Inject constructor() : AbstractRestoreService() {
    override val mTAG: String = "RestoreServiceCloudImpl"

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
            opType = OpType.RESTORE,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun prepareSource() {
        mCloudRepo.getClient(requestedCloudName).also { (c, e) ->
            mCloudEntity = e
            mClient = c
        }

        mRemotePath = requestedBackupDir ?: mCloudEntity.remote
        mRemoteAppsDir = mPathUtil.getCloudRemoteAppsDir(mRemotePath)
        mTaskEntity.update(cloud = mCloudEntity.name, backupDir = mRemotePath)
    }

    private fun getRemoteAppDir(archivesRelativeDir: String) = "${mRemoteAppsDir}/${archivesRelativeDir}"

    override suspend fun clear() = clearCloudWorkspace(mRootDir, if (::mClient.isInitialized) mClient else null)

    override suspend fun restore(type: DataType, userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String) {
        val remoteAppDir = getRemoteAppDir(p.archivesRelativeDir)
        mPackagesRestoreUtil.download(client = mClient, p = p, t = t, dataType = type, srcDir = remoteAppDir) { dir ->
            if (type == DataType.PACKAGE_APK) {
                mPackagesRestoreUtil.restoreApk(userId = userId, p = p, t = t, srcDir = dir)
            } else {
                mPackagesRestoreUtil.restoreData(userId = userId, p = p, t = t, dataType = type, srcDir = dir)
            }
        }
    }

    @Inject
    override lateinit var mPackagesRestoreUtil: PackagesRestoreUtil

    override val mRootDir by lazy { mPathUtil.newCloudTmpDir() }
    override val mAppsDir by lazy { "$mRootDir/${PathUtil.getAppsRelativeDir()}" }

    @Inject
    lateinit var mCloudRepo: CloudRepository

    private lateinit var mCloudEntity: CloudEntity
    private lateinit var mClient: CloudClient
    private lateinit var mRemotePath: String
    private lateinit var mRemoteAppsDir: String
}
