package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.AppBackupDao
import com.xayah.core.datastore.readFastSameVersionBackup
import com.xayah.core.model.AppBackupOverview
import com.xayah.core.model.AppNoteItem
import com.xayah.core.model.AppKey
import com.xayah.core.model.BACKUP_MANIFEST_SCHEMA_VERSION
import com.xayah.core.model.BackupAppEntity
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupManifest
import com.xayah.core.model.BackupManifestFile
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.findRetentionRevisions
import com.xayah.core.model.toPackageEntity
import com.xayah.core.model.toBackupApp
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.ConfigsPackageRestoreName
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppBackupDao,
    private val packageRepository: PackageRepository,
    private val appIconRepository: AppIconRepository,
    private val xposedModuleDetector: XposedModuleDetector,
    private val rootService: RemoteRootService,
    private val pathUtil: PathUtil,
) {
    private val _verificationResults = MutableStateFlow<Map<String, VerificationResult>>(emptyMap())
    val verificationResults = _verificationResults.asStateFlow()

    data class RestoreSelection(val cloudName: String, val backupDir: String)
    data class VerificationReport(
        val results: List<VerificationResult>,
    )
    data class VerificationResult(
        val revision: BackupRevisionEntity,
        val appLabel: String,
        val status: BackupVerificationStatus,
        val issues: List<VerificationIssue>,
        val iconRepaired: Boolean = false,
    )
    data class VerificationIssue(
        val type: VerificationIssueType,
        val fileName: String? = null,
    )
    enum class VerificationIssueType {
        MANIFEST_MISSING,
        MANIFEST_INVALID,
        METADATA_MISMATCH,
        FILE_MISSING,
        FILE_SIZE_MISMATCH,
        FILE_CHECKSUM_MISMATCH,
        NOT_LOCAL,
    }
    data class CleanupReport(
        val deletedRevisionIds: Set<String>,
        val failedCount: Int,
    )
    data class RebuildReport(
        val scannedCount: Int,
        val results: List<RebuildResult>,
    )
    data class RebuildResult(
        val appLabel: String,
        val revision: BackupRevisionEntity,
    )
    data class RetentionCandidate(
        val appLabel: String,
        val revision: BackupRevisionEntity,
        val removable: Boolean,
    )

    fun observeApps(): Flow<List<AppBackupOverview>> = dao.observeApps()

    fun observeApp(packageName: String, userId: Int): Flow<BackupAppEntity?> =
        dao.observeApp(packageName, userId)

    fun observeRevisions(packageName: String, userId: Int): Flow<List<BackupRevisionEntity>> =
        dao.observeRevisions(packageName, userId)

    fun observeRevisions(): Flow<List<BackupRevisionEntity>> = dao.observeRevisions()

    suspend fun getRevisionSizeBytes(repositoryId: String, artifactIds: List<String>): Long =
        if (artifactIds.isEmpty()) 0 else dao.getRevisionSizeBytes(repositoryId, artifactIds)

    suspend fun syncInstalledApps(userId: Int, apps: List<PackageEntity>) {
        val indexedApps = apps.map { app ->
            app.toBackupApp(note = dao.getApp(app.packageName, app.userId)?.note.orEmpty())
        }
        dao.replaceInstalledApps(userId, indexedApps)
    }

    suspend fun updateAppNote(packageName: String, userId: Int, note: String) {
        dao.updateAppNote(packageName, userId, note.trim())
        removeUnusedApps(userId)
    }

    suspend fun getAppNotes(): List<AppNoteItem> = dao.getAppsWithNotes().map {
        AppNoteItem(it.packageName, it.userId, it.note)
    }

    suspend fun importAppNotes(notes: List<AppNoteItem>) {
        notes.forEach { item ->
            val app = dao.getApp(item.packageName, item.userId)
            if (app != null) {
                dao.updateAppNote(item.packageName, item.userId, item.note.trim())
            } else if (item.note.isNotBlank()) {
                dao.upsertApps(
                    listOf(
                        AppKey(item.packageName, item.userId).toBackupApp().copy(note = item.note.trim())
                    )
                )
            }
        }
    }

    suspend fun removeUnusedApps(userId: Int) {
        dao.deleteUninstalledAppsWithoutRevisions(userId)
    }

    suspend fun removeUnusedApps() {
        dao.deleteUninstalledAppsWithoutRevisions()
    }

    suspend fun recordLegacyRevision(
        app: PackageEntity,
        createdAt: Long,
        repositoryId: String,
        contentMask: Int,
        sizeBytes: Long,
    ) {
        dao.upsertApps(listOf(app.toBackupApp(note = dao.getApp(app.packageName, app.userId)?.note.orEmpty())))
        dao.upsertRevision(
            BackupRevisionEntity(
                packageName = app.packageName,
                userId = app.userId,
                createdAt = createdAt,
                appVersionName = app.packageInfo.versionName,
                appVersionCode = app.packageInfo.versionCode,
                engine = BackupEngine.LEGACY,
                repositoryId = repositoryId,
                artifactId = app.archivesRelativeDir,
                contentMask = contentMask,
                sizeBytes = sizeBytes,
            )
        )
    }

    suspend fun getLatestReusableLegacyRevision(app: PackageEntity, repositoryId: String): PackageEntity? {
        if (!app.apkSelected || !context.readFastSameVersionBackup().first()) return null
        val revision = dao.getLatestRevision(app.packageName, app.userId, repositoryId) ?: return null
        val previous = findLegacyRevision(revision) ?: return null
        if (!app.canReuseApkFrom(previous)) return null
        val revisionDir = getLocalRevisionDir(revision) ?: return null
        val manifest = rootService.readJson<BackupManifest>(PathUtil.getBackupManifestDst(revisionDir)) ?: return null
        if (!matchesManifest(revision, manifest) || !hasValidManifestFiles(manifest.files)) return null
        val file = manifest.files.orEmpty().firstOrNull { it.name == "apk.${app.indexInfo.compressionType.suffix}" } ?: return null
        if (getManifestFile("$revisionDir/${file.name}") != file) return null
        return previous
    }

    suspend fun getManifestFile(path: String): BackupManifestFile? {
        val digest = rootService.calculateSHA256(path) ?: return null
        return BackupManifestFile(PathUtil.getFileName(path), rootService.calculateSize(path), digest)
    }

    suspend fun writeManifest(
        app: PackageEntity,
        createdAt: Long,
        revisionDir: String,
        note: String = "",
        uploadedFiles: List<BackupManifestFile> = emptyList(),
    ): BackupManifest? {
        val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
        val localFiles = rootService.listFilePaths(revisionDir, listFiles = true, listDirs = false)
            .filterNot { it == manifestPath }
            .map { path -> getManifestFile(path) ?: return null }
        val files = (uploadedFiles + localFiles).sortedBy(BackupManifestFile::name)
        if (files.isEmpty()) return null
        val contentMask = files.fold(0) { mask, file ->
            mask or when (file.name.substringBefore('.')) {
                DataType.PACKAGE_APK.type -> 1
                DataType.PACKAGE_USER.type -> 2
                DataType.PACKAGE_USER_DE.type -> 4
                DataType.PACKAGE_DATA.type -> 8
                DataType.PACKAGE_OBB.type -> 16
                DataType.PACKAGE_MEDIA.type -> 32
                else -> 0
            }
        }
        val manifest = BackupManifest(
            packageName = app.packageName,
            userId = app.userId,
            createdAt = createdAt,
            versionName = app.packageInfo.versionName,
            versionCode = app.packageInfo.versionCode,
            contentMask = contentMask,
            note = note.trim(),
            files = files,
        )
        return manifest.takeIf { rootService.writeJson(data = it, dst = manifestPath).isSuccess }
    }

    fun rememberVerification(result: VerificationResult) {
        if (result.status != BackupVerificationStatus.NOT_VERIFIED) {
            _verificationResults.value += result.revision.id to result
        }
    }

    suspend fun saveBackupIcon(packageName: String, appsDir: String, revisionDir: String): String? {
        appIconRepository.saveInstalledIcon(packageName, appsDir)?.let { return it }
        appIconRepository.repairFromBackup(revisionDir, appsDir, packageName, overwrite = true)
        return PathUtil.getAppIconPath(appsDir, packageName).takeIf { rootService.exists(it) }
    }

    suspend fun inspectRevision(revision: BackupRevisionEntity): VerificationResult = withContext(Dispatchers.IO) {
        val result = inspectRevisionFiles(revision)
        val revisionDir = getLocalRevisionDir(revision)
        revisionDir?.let {
            refreshXposedModule(revision, it)
            if (result.status == BackupVerificationStatus.VALID) refreshDisplayStats(revision, it)
        }
        val iconRepaired = if (revisionDir != null) {
            appIconRepository.repairFromBackup(
                revisionDir = revisionDir,
                appsDir = PathUtil.getParentPath(PathUtil.getParentPath(revisionDir)),
                packageName = revision.packageName,
            )
        } else false
        result.copy(iconRepaired = iconRepaired)
    }

    internal suspend fun calculateDisplayStats(
        revisionDir: String,
        compression: CompressionType,
        states: PackageDataStates,
    ) = PackageDataStats().apply {
        suspend fun size(type: DataType, selected: Boolean) = if (selected) {
            rootService.calculateSize("$revisionDir/${type.type}.${compression.suffix}")
        } else 0L

        apkBytes = size(DataType.PACKAGE_APK, states.apkState == DataState.Selected)
        userBytes = size(DataType.PACKAGE_USER, states.userState == DataState.Selected)
        userDeBytes = size(DataType.PACKAGE_USER_DE, states.userDeState == DataState.Selected)
        dataBytes = size(DataType.PACKAGE_DATA, states.dataState == DataState.Selected)
        obbBytes = size(DataType.PACKAGE_OBB, states.obbState == DataState.Selected)
        mediaBytes = size(DataType.PACKAGE_MEDIA, states.mediaState == DataState.Selected)
    }

    private suspend fun refreshDisplayStats(revision: BackupRevisionEntity, revisionDir: String) {
        val configPath = PathUtil.getPackageRestoreConfigDst(revisionDir)
        val config = rootService.readJson<PackageRestoreConfig>(configPath) ?: return
        val stats = calculateDisplayStats(revisionDir, config.compressionType, config.dataStates)
        if (config.displayStats == stats) return
        val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
        val manifest = rootService.readJson<BackupManifest>(manifestPath) ?: return
        if (!rootService.writeJson(config.copy(displayStats = stats), configPath).isSuccess) return
        val configDigest = rootService.calculateSHA256(configPath) ?: return
        val configFile = BackupManifestFile(
            name = ConfigsPackageRestoreName,
            sizeBytes = rootService.calculateSize(configPath),
            sha256 = configDigest,
        )
        val files = (manifest.files.orEmpty().filterNot { it.name == ConfigsPackageRestoreName } + configFile)
            .sortedBy(BackupManifestFile::name)
        if (!rootService.writeJson(manifest.copy(files = files), manifestPath).isSuccess) return
        dao.upsertRevision(revision.copy(sizeBytes = files.sumOf(BackupManifestFile::sizeBytes)))
        packageRepository.getRevisions(revision.packageName, revision.userId, revision.createdAt)
            .firstOrNull {
                it.indexInfo.cloud.isEmpty() &&
                    it.indexInfo.backupDir == context.localBackupSaveDir()
            }
            ?.let { packageRepository.upsert(it.copy(displayStats = stats)) }
    }

    private suspend fun refreshXposedModule(revision: BackupRevisionEntity, revisionDir: String) {
        val isXposedModule = detectXposedModule(revision.packageName, revision.userId, revisionDir)
            ?: return
        packageRepository.getPackage(revision.packageName, OpType.BACKUP, revision.userId)?.let { app ->
            if (app.packageInfo.isXposedModule != isXposedModule) {
                app.packageInfo.isXposedModule = isXposedModule
                packageRepository.upsert(app)
            }
        }
        val preserveId = revision.artifactId.substringAfterLast('@').toLongOrNull() ?: return
        packageRepository.updateRevisionXposedModule(
            packageName = revision.packageName,
            userId = revision.userId,
            preserveId = preserveId,
            cloud = revision.repositoryId.substringBefore(':'),
            backupDir = revision.repositoryId.substringAfter(':'),
            isXposedModule = isXposedModule,
        )
    }

    private suspend fun detectXposedModule(packageName: String, userId: Int, revisionDir: String): Boolean? =
        xposedModuleDetector.isInstalledModule(packageName, userId)
            ?: xposedModuleDetector.isModuleInBackup(revisionDir)

    private suspend fun inspectRevisionFiles(revision: BackupRevisionEntity): VerificationResult {
        val appLabel = dao.getApp(revision.packageName, revision.userId)?.label ?: revision.packageName
        val revisionDir = getLocalRevisionDir(revision) ?: return VerificationResult(
            revision = revision,
            appLabel = appLabel,
            status = BackupVerificationStatus.NOT_VERIFIED,
            issues = listOf(VerificationIssue(VerificationIssueType.NOT_LOCAL)),
        )
        val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
        if (!rootService.exists(manifestPath)) {
            return VerificationResult(
                revision,
                appLabel,
                BackupVerificationStatus.DAMAGED,
                listOf(VerificationIssue(VerificationIssueType.MANIFEST_MISSING)),
            )
        }
        val manifest = rootService.readJson<BackupManifest>(manifestPath) ?: return VerificationResult(
            revision,
            appLabel,
            BackupVerificationStatus.DAMAGED,
            listOf(VerificationIssue(VerificationIssueType.MANIFEST_INVALID)),
        )
        if (!hasValidManifestFiles(manifest.files)) return VerificationResult(
            revision,
            appLabel,
            BackupVerificationStatus.DAMAGED,
            listOf(VerificationIssue(VerificationIssueType.MANIFEST_INVALID)),
        )
        val issues = mutableListOf<VerificationIssue>()
        if (!matchesManifest(revision, manifest)) issues += VerificationIssue(VerificationIssueType.METADATA_MISMATCH)
        manifest.files.orEmpty().forEach { file ->
            val path = "$revisionDir/${file.name}"
            when {
                !rootService.exists(path) -> issues += VerificationIssue(VerificationIssueType.FILE_MISSING, file.name)
                rootService.calculateSize(path) != file.sizeBytes ->
                    issues += VerificationIssue(VerificationIssueType.FILE_SIZE_MISMATCH, file.name)
                rootService.calculateSHA256(path) != file.sha256 ->
                    issues += VerificationIssue(VerificationIssueType.FILE_CHECKSUM_MISMATCH, file.name)
            }
        }
        return VerificationResult(
            revision = revision,
            appLabel = appLabel,
            status = if (issues.isEmpty()) BackupVerificationStatus.VALID else BackupVerificationStatus.DAMAGED,
            issues = issues,
        )
    }

    suspend fun verifyAllLocal(onProgress: suspend (Int, Int) -> Unit): VerificationReport {
        val revisions = dao.getRevisions(":${context.localBackupSaveDir()}")
        val results = mutableListOf<VerificationResult>()
        onProgress(0, revisions.size)
        revisions.forEachIndexed { index, revision ->
            results += inspectRevision(revision)
            rememberVerification(results.last())
            onProgress(index + 1, revisions.size)
        }
        return VerificationReport(results)
    }

    suspend fun deleteFailedLocalBackups(
        results: List<VerificationResult>,
        onProgress: suspend (Int, Int) -> Unit,
    ): CleanupReport {
        val failed = results.filter { it.status != BackupVerificationStatus.VALID }
        val deletedIds = mutableSetOf<String>()
        onProgress(0, failed.size)
        failed.forEachIndexed { index, result ->
            if (deleteRevision(result.revision)) deletedIds += result.revision.id
            onProgress(index + 1, failed.size)
        }
        return CleanupReport(
            deletedRevisionIds = deletedIds,
            failedCount = failed.size - deletedIds.size,
        )
    }

    suspend fun rebuildLocalIndex(onProgress: suspend (Int, Int, Int) -> Unit): RebuildReport {
        val backupDir = context.localBackupSaveDir()
        val repositoryId = ":$backupDir"
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val rebuilt = mutableListOf<Pair<PackageEntity, BackupManifest>>()
        val packageDirs = rootService.listFilePathsChecked(appsDir, listFiles = false, listDirs = true).getOrThrow()
        val revisionDirs = buildList {
            packageDirs.forEach { packageDir ->
                addAll(rootService.listFilePathsChecked(packageDir, listFiles = false, listDirs = true).getOrThrow())
            }
        }
        onProgress(0, revisionDirs.size, 0)
        revisionDirs.forEachIndexed { index, revisionDir ->
            val config = rootService.readJson<PackageRestoreConfig>(PathUtil.getPackageRestoreConfigDst(revisionDir))
            val manifest = rootService.readJson<BackupManifest>(PathUtil.getBackupManifestDst(revisionDir))
            val app = runCatching {
                if (
                    config != null &&
                    manifest != null &&
                    config.schemaVersion == PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION &&
                    manifest.schemaVersion == BACKUP_MANIFEST_SCHEMA_VERSION &&
                    config.packageName == manifest.packageName &&
                    config.userId == manifest.userId &&
                    config.createdAt == manifest.createdAt &&
                    config.versionName == manifest.versionName &&
                    config.versionCode == manifest.versionCode &&
                    hasValidManifestFiles(manifest.files) &&
                    PathUtil.getPackageArchiveRelativeDir(config.packageName, config.userId, config.createdAt)
                        ?.let { FileUtil.normalizeAbsolutePath("$appsDir/$it") } == FileUtil.normalizeAbsolutePath(revisionDir)
                ) config.toPackageEntity(cloud = "", backupDir = backupDir) else null
            }.getOrNull()
            if (app != null && manifest != null) {
                detectXposedModule(app.packageName, app.userId, revisionDir)?.let {
                    app.packageInfo.isXposedModule = it
                }
                rebuilt += app to manifest
            }
            onProgress(index + 1, revisionDirs.size, rebuilt.size)
        }
        packageRepository.replaceLocalRestoreIndex(backupDir, rebuilt.map { it.first })
        val apps = rebuilt
            .distinctBy { (app, _) -> app.packageName to app.userId }
            .map { (app, _) ->
                val existing = dao.getApp(app.packageName, app.userId)
                existing?.takeIf { it.isInstalled }
                    ?: app.toBackupApp(isInstalled = false, note = existing?.note.orEmpty())
            }
        val revisions = rebuilt.map { (app, manifest) ->
            app.toRevision(
                repositoryId = repositoryId,
                contentMask = manifest.contentMask,
                sizeBytes = manifest.files.orEmpty().sumOf(BackupManifestFile::sizeBytes),
                note = manifest.note.orEmpty(),
            )
        }
        dao.replaceRepositoryIndex(repositoryId, apps, revisions)
        dao.deleteUninstalledAppsWithoutRevisions()
        return RebuildReport(
            scannedCount = revisionDirs.size,
            results = rebuilt.zip(revisions) { (app, _), revision ->
                RebuildResult(app.packageInfo.label, revision)
            },
        )
    }

    suspend fun deleteRevision(revision: BackupRevisionEntity): Boolean {
        if (!hasValidArtifactId(revision)) return false
        val packageEntity = findLegacyRevision(revision)
        val deleted = if (packageEntity != null) {
            packageRepository.delete(packageEntity)
            packageRepository.getPackage(packageEntity.id) == null
        } else {
            val revisionDir = getLocalRevisionDir(revision) ?: return false
            rootService.exists(revisionDir).not() || rootService.deleteRecursively(revisionDir)
        }
        if (!deleted) return false
        dao.deleteRevisionAndSync(revision)
        _verificationResults.value -= revision.id
        getLocalRevisionDir(revision)?.let { revisionDir ->
            val packageDir = PathUtil.getParentPath(revisionDir)
            val iconPath = PathUtil.getAppIconPath(PathUtil.getParentPath(packageDir), revision.packageName)
            val remaining = rootService.listFilePathsChecked(packageDir).getOrNull()
            if (remaining != null && remaining.all { it == iconPath }) {
                rootService.deleteRecursively(iconPath)
                rootService.clearEmptyDirectoriesRecursively(packageDir)
            }
        }
        dao.deleteUninstalledAppsWithoutRevisions(revision.userId)
        return true
    }

    suspend fun getLocalRevisionCount(): Int = dao.getRevisions(":${context.localBackupSaveDir()}").size

    suspend fun getLocalRetentionCandidates(retainCount: Int): List<RetentionCandidate> {
        require(retainCount >= 1)
        return findRetentionRevisions(dao.getRevisions(":${context.localBackupSaveDir()}"), retainCount)
            .groupBy { AppKey(it.revision.packageName, it.revision.userId) }
            .flatMap { (key, revisions) ->
                val appLabel = dao.getApp(key.packageName, key.userId)?.label ?: key.packageName
                revisions.map { RetentionCandidate(appLabel, it.revision, it.removable) }
            }
    }

    suspend fun updateRevisionNote(revision: BackupRevisionEntity, note: String): Boolean {
        val normalized = note.trim()
        val revisionDir = getLocalRevisionDir(revision) ?: return false
        val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
        val manifest = rootService.readJson<BackupManifest>(manifestPath) ?: return false
        if (!rootService.writeJson(manifest.copy(note = normalized), manifestPath).isSuccess) return false
        dao.updateRevisionNote(revision.id, normalized)
        return true
    }

    suspend fun selectRevisionForRestore(revision: BackupRevisionEntity, dataStates: PackageDataStates): RestoreSelection? {
        val packageEntity = findLegacyRevision(revision) ?: return null
        packageRepository.selectOnlyForRestore(packageEntity.id, dataStates)
        return RestoreSelection(
            cloudName = packageEntity.indexInfo.cloud,
            backupDir = packageEntity.indexInfo.backupDir,
        )
    }

    suspend fun selectLatestLocalRevisionsForRestore(keys: Set<AppKey>): RestoreSelection? {
        val backupDir = context.localBackupSaveDir()
        val repositoryId = ":$backupDir"
        val latest = dao.getRevisions(repositoryId)
            .asSequence()
            .filter { AppKey(it.packageName, it.userId) in keys }
            .distinctBy { AppKey(it.packageName, it.userId) }
            .toList()
        val revisions = buildList {
            latest.forEach { revision ->
                findLegacyRevision(revision)?.let { app -> add(app to revision.contentMask.toDataStates()) }
            }
        }
        if (revisions.isEmpty()) return null
        packageRepository.selectOnlyForRestore(revisions)
        return RestoreSelection(cloudName = "", backupDir = backupDir)
    }

    private suspend fun findLegacyRevision(revision: BackupRevisionEntity): PackageEntity? {
        if (revision.engine != BackupEngine.LEGACY || !hasValidArtifactId(revision)) return null
        val preserveId = revision.artifactId.substringAfterLast('@').toLongOrNull() ?: return null
        return packageRepository
            .getRevisions(revision.packageName, revision.userId, preserveId)
            .firstOrNull { "${it.indexInfo.cloud}:${it.indexInfo.backupDir}" == revision.repositoryId }
    }

    private fun getLocalRevisionDir(revision: BackupRevisionEntity): String? {
        if (revision.engine != BackupEngine.LEGACY || !hasValidArtifactId(revision)) return null
        val cloud = revision.repositoryId.substringBefore(':')
        if (cloud.isNotEmpty()) return null
        val backupDir = revision.repositoryId.substringAfter(':')
        val normalized = FileUtil.normalizeAbsolutePath(backupDir) ?: return null
        return "$normalized/${PathUtil.getAppsRelativeDir()}/${revision.artifactId}"
    }

    private fun hasValidArtifactId(revision: BackupRevisionEntity): Boolean =
        PathUtil.getPackageArchiveRelativeDir(revision.packageName, revision.userId, revision.createdAt)
            ?.let { it == revision.artifactId } == true

    private fun matchesManifest(revision: BackupRevisionEntity, manifest: BackupManifest): Boolean =
        manifest.schemaVersion == BACKUP_MANIFEST_SCHEMA_VERSION && manifest.packageName == revision.packageName &&
            manifest.userId == revision.userId && manifest.createdAt == revision.createdAt &&
            manifest.versionName == revision.appVersionName && manifest.versionCode == revision.appVersionCode &&
            manifest.contentMask == revision.contentMask && manifest.note.orEmpty() == revision.note

    private fun hasValidManifestFiles(files: List<BackupManifestFile?>?): Boolean =
        !files.isNullOrEmpty() && files.all { file ->
            val name = file?.name
            !name.isNullOrEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
        }

    private fun Int.toDataStates() = PackageDataStates(
        apkState = if (this and 1 != 0) DataState.Selected else DataState.NotSelected,
        userState = if (this and 2 != 0) DataState.Selected else DataState.NotSelected,
        userDeState = if (this and 4 != 0) DataState.Selected else DataState.NotSelected,
        dataState = if (this and 8 != 0) DataState.Selected else DataState.NotSelected,
        obbState = if (this and 16 != 0) DataState.Selected else DataState.NotSelected,
        mediaState = if (this and 32 != 0) DataState.Selected else DataState.NotSelected,
    )

    private fun PackageEntity.toBackupApp(isInstalled: Boolean = true, note: String = "") = BackupAppEntity(
        packageName = packageName,
        userId = userId,
        label = packageInfo.label,
        versionName = packageInfo.versionName,
        versionCode = packageInfo.versionCode,
        firstInstallTime = packageInfo.firstInstallTime,
        lastUpdateTime = packageInfo.lastUpdateTime,
        isSystem = isSystemApp,
        isInstalled = isInstalled,
        note = note,
    )

    private fun PackageEntity.toRevision(
        repositoryId: String,
        contentMask: Int,
        sizeBytes: Long,
        note: String = "",
    ) = BackupRevisionEntity(
        packageName = packageName,
        userId = userId,
        createdAt = preserveId,
        appVersionName = packageInfo.versionName,
        appVersionCode = packageInfo.versionCode,
        engine = BackupEngine.LEGACY,
        repositoryId = repositoryId,
        artifactId = archivesRelativeDir,
        contentMask = contentMask,
        sizeBytes = sizeBytes,
        note = note,
    )
}
