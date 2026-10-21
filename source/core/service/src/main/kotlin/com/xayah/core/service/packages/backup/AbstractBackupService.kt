package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.BackupRequestStore
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.datastore.saveLastBackupTime
import com.xayah.core.model.DataType
import com.xayah.core.model.BackupManifestFile
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.toRestoreConfig
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.service.model.NecessaryInfo
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import com.xayah.core.util.withLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal abstract class AbstractBackupService : AbstractPackagesService() {
    override suspend fun onInitializingPreprocessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.necessary_preparations),
                type = ProcessingType.PREPROCESSING,
                infoType = ProcessingInfoType.NECESSARY_PREPARATIONS
            ).apply {
                id = mTaskDao.upsert(this)
            })
        }
    }

    override suspend fun onInitializingPostProcessingEntities(entities: MutableList<ProcessingInfoEntity>) {
        entities.apply {
            add(ProcessingInfoEntity(
                taskId = mTaskEntity.id,
                title = mContext.getString(R.string.backup_itself),
                type = ProcessingType.POST_PROCESSING,
                infoType = ProcessingInfoType.BACKUP_ITSELF
            ).apply {
                id = mTaskDao.upsert(this)
            })
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
        val packages = mBackupRequestStore.packages.value
        packages.forEach { pkg ->
            mPkgEntities.add(
                TaskDetailPackageEntity(
                    taskId = mTaskEntity.id,
                    packageEntity = pkg,
                    apkInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_APK.type.uppercase())),
                    userInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER.type.uppercase())),
                    userDeInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_USER_DE.type.uppercase())),
                    dataInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_DATA.type.uppercase())),
                    obbInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_OBB.type.uppercase())),
                    mediaInfo = Info(title = mContext.getString(com.xayah.core.data.R.string.args_backup, DataType.PACKAGE_MEDIA.type.uppercase())),
                ).apply {
                    id = mTaskDao.upsert(this)
                }
            )
        }
        mBackupRequestStore.clear()
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun createDirectory(path: String): Boolean = mRootService.mkdirs(path)
    protected open suspend fun onAppDirCreated(archivesRelativeDir: String): Boolean = true
    abstract suspend fun backup(type: DataType, p: PackageEntity, previous: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String)
    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String): Boolean = true
    protected open suspend fun onManifestSaved(path: String, archivesRelativeDir: String): Boolean = true
    protected open suspend fun onAppIconSaved(path: String, packageName: String): Boolean = true
    protected open suspend fun onBackupFailed(archivesRelativeDir: String) {}
    protected open val uploadedManifestFiles: List<BackupManifestFile> get() = emptyList()
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}

    protected abstract val mPackagesBackupUtil: PackagesBackupUtil
    protected abstract val mAppBackupRepository: AppBackupRepository
    protected abstract val mBackupRequestStore: BackupRequestStore

    private lateinit var necessaryInfo: NecessaryInfo

    override suspend fun clear() {
        if (::necessaryInfo.isInitialized) {
            if (necessaryInfo.inputMethods.isNotEmpty()) PreparationUtil.setInputMethods(necessaryInfo.inputMethods)
            if (necessaryInfo.accessibilityServices.isNotEmpty()) PreparationUtil.setAccessibilityServices(necessaryInfo.accessibilityServices)
        }
    }

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                /**
                 * Somehow the input methods and accessibility services
                 * will be changed after backing up on some devices,
                 * so we restore them manually.
                 */
                necessaryInfo = NecessaryInfo(
                    inputMethods = PreparationUtil.getInputMethods().outString.trim().takeUnless { it == "null" }.orEmpty(),
                    accessibilityServices = PreparationUtil.getAccessibilityServices().outString.trim().takeUnless { it == "null" }.orEmpty(),
                )
                log { "InputMethods: ${necessaryInfo.inputMethods}." }
                log { "AccessibilityServices: ${necessaryInfo.accessibilityServices}." }

                log { "Trying to create: $mAppsDir." }
                log { "Trying to create: $mConfigsDir." }
                val result = runCatching {
                    check(createDirectory(mAppsDir)) { "Unable to create $mAppsDir" }
                    check(createDirectory(mConfigsDir)) { "Unable to create $mConfigsDir" }
                    onTargetDirsCreated()
                }.onFailure { if (it is CancellationException) throw it }.withLog()
                entity.update(
                    progress = 1f,
                    state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR,
                    log = result.exceptionOrNull()?.message,
                )
            }

            else -> {}
        }
    }

    override suspend fun onProcessing() {
        // createTargetDirs() before readStatFs().
        mTaskEntity.update(
            rawBytes = mTaskRepo.getRawBytes(TaskType.PACKAGE, mPkgEntities.map { it.packageEntity }),
            availableBytes = mTaskRepo.getAvailableBytes(OpType.BACKUP),
            totalBytes = mTaskRepo.getTotalBytes(OpType.BACKUP),
            totalCount = mPkgEntities.size,
        )
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        mPkgEntities.forEach { pkg ->
            var pendingArchiveDir: String? = null
            val pausedPids = if (killAppOption == KillAppOption.OPTION_III) {
                log { "Trying to pause ${pkg.packageEntity.packageName}." }
                mRootService.pausePackage(pkg.packageEntity.packageName, pkg.packageEntity.userId) ?: run {
                    log { "Failed to pause ${pkg.packageEntity.packageName}, falling back to force-stop." }
                    killApp(killAppOption, pkg)
                    intArrayOf()
                }
            } else {
                killApp(killAppOption, pkg)
                intArrayOf()
            }
            executeAtLeast(finalizer = {
                withContext(NonCancellable) {
                    pendingArchiveDir?.let {
                        runCatchingOnService { onBackupFailed(it) }
                        mRootService.deleteRecursively("$mAppsDir/$it")
                    }
                    if (pausedPids.isNotEmpty()) {
                        log { "Resuming ${pkg.packageEntity.packageName}." }
                        mRootService.resumeProcesses(pausedPids)
                    }
                }
            }) {
                log { "Current package: ${pkg.packageEntity}" }

                pkg.update(state = OperationState.PROCESSING)
                val installedApp = pkg.packageEntity
                val revisionCreatedAt = DateUtil.getTimestamp()
                val revisionApp = installedApp.copy(
                    indexInfo = installedApp.indexInfo.copy(preserveId = revisionCreatedAt),
                    packageInfo = installedApp.packageInfo.copy(),
                    extraInfo = installedApp.extraInfo.copy(),
                    dataStates = installedApp.dataStates.copy(),
                    storageStats = installedApp.storageStats.copy(),
                    dataStats = installedApp.dataStats.copy(),
                    displayStats = installedApp.displayStats.copy(),
                )
                val dstDir = "${mAppsDir}/${revisionApp.archivesRelativeDir}"
                val repositoryId = "${mTaskEntity.cloud}:${mTaskEntity.backupDir}"
                val previousRevision = mAppBackupRepository.getLatestReusableLegacyRevision(revisionApp, repositoryId)
                val directoryCreated = withContext(NonCancellable) {
                    createDirectory(dstDir).also {
                        if (it) pendingArchiveDir = revisionApp.archivesRelativeDir
                    }
                }
                if (directoryCreated && onAppDirCreated(archivesRelativeDir = revisionApp.archivesRelativeDir)) {
                    backup(type = DataType.PACKAGE_APK, p = revisionApp, previous = previousRevision, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER, p = revisionApp, previous = previousRevision, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_USER_DE, p = revisionApp, previous = previousRevision, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_DATA, p = revisionApp, previous = previousRevision, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_OBB, p = revisionApp, previous = previousRevision, t = pkg, dstDir = dstDir)
                    backup(type = DataType.PACKAGE_MEDIA, p = revisionApp, previous = previousRevision, t = pkg, dstDir = dstDir)
                    mPackagesBackupUtil.backupPermissions(p = revisionApp)
                    mPackagesBackupUtil.backupSsaid(p = revisionApp)

                    if (pkg.isSuccess) {
                        revisionApp.extraInfo.lastBackupTime = revisionCreatedAt
                        val restoreEntity = revisionApp.copy(
                            id = 0,
                            indexInfo = revisionApp.indexInfo.copy(
                                opType = OpType.RESTORE,
                                cloud = mTaskEntity.cloud,
                                backupDir = mTaskEntity.backupDir,
                            ),
                            extraInfo = revisionApp.extraInfo.copy(activated = false),
                        )
                        val configDst = PathUtil.getPackageRestoreConfigDst(dstDir = dstDir)
                        val configSaved = mRootService.writeJson(data = restoreEntity.toRestoreConfig(), dst = configDst).isSuccess &&
                            onConfigSaved(path = configDst, archivesRelativeDir = revisionApp.archivesRelativeDir)
                        val manifest = if (configSaved) {
                            mAppBackupRepository.writeManifest(revisionApp, revisionCreatedAt, dstDir, uploadedFiles = uploadedManifestFiles)
                        } else {
                            null
                        }
                        if (
                            manifest != null && onManifestSaved(
                                path = PathUtil.getBackupManifestDst(dstDir),
                                archivesRelativeDir = revisionApp.archivesRelativeDir,
                            )
                        ) {
                            mAppBackupRepository.saveBackupIcon(revisionApp.packageName, mAppsDir, dstDir)?.let { iconPath ->
                                onAppIconSaved(iconPath, revisionApp.packageName)
                            }
                            withContext(NonCancellable) {
                                mPackageDao.upsert(restoreEntity)
                                installedApp.extraInfo.lastBackupTime = revisionCreatedAt
                                mAppBackupRepository.recordLegacyRevision(
                                    app = revisionApp,
                                    createdAt = revisionCreatedAt,
                                    repositoryId = repositoryId,
                                    contentMask = manifest.contentMask,
                                    sizeBytes = manifest.files.orEmpty().sumOf { it.sizeBytes },
                                )
                                pendingArchiveDir = null
                            }
                            pkg.update(packageEntity = installedApp)
                            mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                        } else {
                            pkg.update(dataType = DataType.PACKAGE_APK, state = OperationState.ERROR)
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        }
                    } else {
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                } else {
                    pkg.update(dataType = DataType.PACKAGE_APK, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_USER_DE, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_DATA, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_OBB, state = OperationState.ERROR)
                    pkg.update(dataType = DataType.PACKAGE_MEDIA, state = OperationState.ERROR)
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
            }
            mTaskEntity.update(processingIndex = mTaskEntity.processingIndex + 1)
        }
    }

    override suspend fun onPostProcessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.BACKUP_ITSELF -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.backup_itself)
                )
                if (mContext.readBackupItself().first()) {
                    log { "Backup itself enabled." }
                    mCommonBackupUtil.backupItself(dstDir = mRootDir).apply {
                        entity.set(state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = outString)
                        if (isSuccess) {
                            onItselfSaved(path = mCommonBackupUtil.getItselfDst(mRootDir), entity = entity)
                        }
                    }
                    entity.update(progress = 1f)
                } else {
                    entity.update(progress = 1f, state = OperationState.SKIP)
                }
            }

            ProcessingInfoType.NECESSARY_REMAINING_DATA_PROCESSING -> {
                NotificationUtil.notify(
                    mContext,
                    mNotificationBuilder,
                    mContext.getString(R.string.backing_up),
                    mContext.getString(R.string.wait_for_remaining_data_processing)
                )

                var isSuccess = true
                val out = mutableListOf<String>()
                if (mContext.readBackupConfigs().first()) {
                    log { "Backup configs enabled." }
                    mCommonBackupUtil.backupConfigs(dstDir = mConfigsDir).also { result ->
                        if (result.isSuccess.not()) {
                            isSuccess = false
                        }
                        out.add(result.outString)
                        if (result.isSuccess) {
                            onConfigsSaved(path = mCommonBackupUtil.getConfigsDst(mConfigsDir), entity = entity)
                            if (entity.state == OperationState.ERROR) {
                                isSuccess = false
                                out.add(entity.log)
                            }
                        }
                    }
                }
                entity.update(progress = 0.5f)

                if (!cleanup()) {
                    isSuccess = false
                }
                entity.set(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        mContext.saveLastBackupTime(mEndTimestamp)
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        completeNotification(mContext.getString(R.string.backup_completed), completionSummary(time))
    }
}
