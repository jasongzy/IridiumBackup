package com.xayah.core.service.packages.backup

import android.annotation.SuppressLint
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.datastore.readKillAppOption
import com.xayah.core.model.DataType
import com.xayah.core.model.BackupManifestFile
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingInfoType
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.Info
import com.xayah.core.model.database.PackageDataStates.Companion.getSelected
import com.xayah.core.model.database.PackageDataStates.Companion.setSelected
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.toRestoreConfig
import com.xayah.core.model.util.get
import com.xayah.core.model.util.set
import com.xayah.core.service.R
import com.xayah.core.util.command.BackupServiceSettings
import com.xayah.core.service.packages.AbstractPackagesService
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.DateUtil
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.withLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal abstract class AbstractBackupService : AbstractPackagesService() {
    protected abstract val mPackageRepo: PackageRepository
    protected abstract val mConfigsDir: String

    private val backupRequest get() = checkNotNull(packageRequest as? PackageTaskRequest.Backup) { mContext.getString(R.string.package_request_expired) }

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
        val packages = backupRequest.packages
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
    }

    override suspend fun beforePreprocessing() {
        NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up), mContext.getString(R.string.preprocessing))
    }

    protected open suspend fun onTargetDirsCreated() {}
    protected open suspend fun createRemoteRevisionDirectory(relativeDir: String) {}
    abstract suspend fun backup(type: DataType, p: PackageEntity, previous: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String)
    protected open suspend fun onConfigSaved(path: String, archivesRelativeDir: String): Boolean = true
    protected open suspend fun onManifestSaved(path: String, archivesRelativeDir: String): Boolean = true
    protected open suspend fun onAppIconSaved(path: String, packageName: String): Boolean = true
    protected open suspend fun onBackupFailed(archivesRelativeDir: String) {}
    protected open val uploadedManifestFiles: List<BackupManifestFile> get() = emptyList()
    protected open suspend fun onItselfSaved(path: String, entity: ProcessingInfoEntity) {}
    protected open suspend fun onConfigsSaved(path: String, entity: ProcessingInfoEntity) {}

    private suspend fun reserveRevisionDirectory(app: PackageEntity): String? {
        val relativeDir = PathUtil.getPackageArchiveRelativeDir(app.packageName, app.userId, app.preserveId) ?: return null
        val directory = "$mAppsDir/$relativeDir"
        if (!mRootService.mkdirs(PathUtil.getParentPath(directory)) || !mRootService.createDirectory(directory)) return null
        var reserved = false
        try {
            reserved = runCatchingOnService { createRemoteRevisionDirectory(relativeDir) }
            return relativeDir.takeIf { reserved }
        } finally {
            if (!reserved) mRootService.removeEntry(directory, directory = true)
        }
    }

    protected abstract val mPackagesBackupUtil: PackagesBackupUtil
    protected abstract val mAppBackupRepository: AppBackupRepository

    private val serviceSettings = BackupServiceSettings()

    override suspend fun onPreprocessing(entity: ProcessingInfoEntity) {
        when (entity.infoType) {
            ProcessingInfoType.NECESSARY_PREPARATIONS -> {
                log { "Trying to create: $mAppsDir." }
                log { "Trying to create: $mConfigsDir." }
                val result = runCatching {
                    check(mRootService.mkdirs(mAppsDir)) { "Unable to create $mAppsDir" }
                    check(mRootService.mkdirs(mConfigsDir)) { "Unable to create $mConfigsDir" }
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
        val storage = mRootService.readStatFs(mRootDir)
        mTaskEntity.update(
            rawBytes = mTaskRepo.getRawBytes(TaskType.PACKAGE, mPkgEntities.map { it.packageEntity }),
            availableBytes = storage.availableBytes.toDouble(),
            totalBytes = storage.totalBytes.toDouble(),
            totalCount = mPkgEntities.size,
        )
        log { "Task count: ${mPkgEntities.size}." }

        val killAppOption = mContext.readKillAppOption().first()
        log { "Kill app option: $killAppOption" }

        mPkgEntities.forEach { pkg ->
            currentCoroutineContext().ensureActive()
            var pendingArchiveDir: String? = null
            var serviceRepairs = emptyList<suspend () -> Unit>()
            val pause = if (killAppOption == KillAppOption.OPTION_III) {
                log { "Trying to pause ${pkg.packageEntity.packageName}." }
                mRootService.pausePackage(pkg.packageEntity.packageName, pkg.packageEntity.userId)
            } else null
            try {
                currentCoroutineContext().ensureActive()
                if (pause == null) {
                    val app = pkg.packageEntity
                    if (killAppOption == KillAppOption.OPTION_II || killAppOption == KillAppOption.OPTION_III) {
                        serviceRepairs = serviceSettings.stop(app.userId, app.packageName) {
                            preparePackage(killAppOption, pkg, app.userId)
                        } ?: return@forEach
                    } else if (!preparePackage(killAppOption, pkg, app.userId)) return@forEach
                }
                log { "Current package: ${pkg.packageEntity.packageName} (user ${pkg.packageEntity.userId})" }

                pkg.update(state = OperationState.PROCESSING)
                val installedApp = pkg.packageEntity
                val revisionCreatedAt = DateUtil.getTimestamp()
                val revisionApp = installedApp.copy(
                    indexInfo = installedApp.indexInfo.copy(preserveId = revisionCreatedAt),
                    packageInfo = installedApp.packageInfo.copy(),
                    extraInfo = installedApp.extraInfo.copy(),
                    dataStates = installedApp.dataStates.copy(),
                    storageStats = installedApp.storageStats.copy(),
                    dataStats = PackageDataStats(),
                    displayStats = PackageDataStats(),
                )
                val repositoryId = "${mTaskEntity.cloud}:${mTaskEntity.backupDir}"
                val previousRevision = mAppBackupRepository.getLatestReusableLegacyRevision(revisionApp, repositoryId)
                val relativeDir = withContext(NonCancellable) {
                    reserveRevisionDirectory(revisionApp).also { pendingArchiveDir = it }
                }
                if (relativeDir != null) {
                    val dstDir = "$mAppsDir/$relativeDir"
                    for (type in DataType.packageArchives) {
                        currentCoroutineContext().ensureActive()
                        backup(type, revisionApp, previousRevision, pkg, dstDir)
                        revisionApp.dataStates = type.setSelected(revisionApp.dataStates, pkg.get(type).state == OperationState.DONE)
                    }
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
                            mAppBackupRepository.writeManifest(revisionApp, revisionCreatedAt, dstDir, knownFiles = uploadedManifestFiles)
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
                                mAppBackupRepository.recordLegacyRevision(
                                    restore = restoreEntity,
                                    contentMask = manifest.contentMask,
                                    sizeBytes = manifest.files.orEmpty().sumOf { it.sizeBytes },
                                )
                                pendingArchiveDir = null
                            }
                            installedApp.extraInfo.lastBackupTime = revisionCreatedAt
                            pkg.update(packageEntity = installedApp)
                            mTaskEntity.update(successCount = mTaskEntity.successCount + 1)
                        } else {
                            val completed = DataType.packageArchives.firstOrNull { it.getSelected(revisionApp.dataStates) }
                            val type = completed ?: DataType.packageArchives.firstOrNull { it.getSelected(installedApp.dataStates) } ?: DataType.PACKAGE_APK
                            val reason = if (completed == null) R.string.backup_no_content else R.string.backup_metadata_failed
                            pkg.update(dataType = type, state = OperationState.ERROR, log = mContext.getString(reason))
                            mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                        }
                    } else {
                        mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                    }
                } else {
                    val message = mContext.getString(R.string.backup_revision_directory_failed, revisionApp.archivesRelativeDir)
                    for (type in DataType.packageArchives) {
                        currentCoroutineContext().ensureActive()
                        pkg.update(dataType = type, state = OperationState.ERROR, log = message.takeIf { type == DataType.PACKAGE_APK })
                    }
                    mTaskEntity.update(failureCount = mTaskEntity.failureCount + 1)
                }
                pkg.update(state = if (pkg.isSuccess) OperationState.DONE else OperationState.ERROR)
            } finally {
                withContext(NonCancellable) {
                    pause?.close()
                    serviceRepairs.forEach { repair -> runCatchingOnService { repair() } }
                    pendingArchiveDir?.let {
                        runCatchingOnService { onBackupFailed(it) }
                        mRootService.deleteRecursively("$mAppsDir/$it")
                    }
                }
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
                if (backupRequest.backupItself) {
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
                if (backupRequest.backupConfigs) {
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
                entity.update(progress = 1f, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
            }

            else -> {}
        }
    }

    override suspend fun afterPostProcessing() {
        val time = DateUtil.getShortRelativeTimeSpanString(context = mContext, time1 = mStartTimestamp, time2 = mEndTimestamp)
        completeNotification(mContext.getString(R.string.backup_completed), completionSummary(time))
    }
}
