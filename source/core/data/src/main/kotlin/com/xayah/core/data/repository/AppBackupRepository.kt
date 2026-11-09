package com.xayah.core.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonParseException
import com.xayah.core.data.R
import com.xayah.core.database.AppDatabase
import com.xayah.core.database.SQLITE_BIND_LIMIT
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
import com.xayah.core.model.ConfigsPackageRestoreName
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.TitaniumSource
import com.xayah.core.model.archiveContentMask
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStatesEntity
import com.xayah.core.model.database.PackageDataStates.Companion.getSelected
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.isProtectedByNote
import com.xayah.core.model.findRetentionRevisions
import com.xayah.core.model.hasValidFileEntries
import com.xayah.core.model.matchesRevision
import com.xayah.core.model.toPackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val packageRepository: PackageRepository,
    private val appIconRepository: AppIconRepository,
    private val xposedModuleDetector: XposedModuleDetector,
    private val rootService: RemoteRootService,
) {
    private val dao = database.appBackupDao()
    private val _verificationResults = MutableStateFlow<Map<String, VerificationResult>>(emptyMap())
    val verificationResults = _verificationResults.asStateFlow()
    private val revisionMutex = Mutex()

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
    ) {
        val canClean get() = status == BackupVerificationStatus.DAMAGED && issues.none {
            it.type == VerificationIssueType.UNREADABLE || it.type == VerificationIssueType.METADATA_MISMATCH
        }
    }
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
        UNREADABLE,
        NOT_LOCAL,
    }
    data class CleanupReport(
        val results: List<VerificationResult>,
        val deletedCount: Int,
        val failedCount: Int,
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

    suspend fun getRestoreSizeBytes(repositoryId: String, packages: List<PackageEntity>): Long {
        val revisions = packages.map { it.archivesRelativeDir }.distinct().chunked(SQLITE_BIND_LIMIT - 1)
            .flatMap { dao.getRevisions(repositoryId, it) }.associateBy { it.artifactId }
        return packages.sumOf { app ->
            val revision = revisions[app.archivesRelativeDir]
            if (revision != null && revision.contentMask == app.selectionFlag && revision.sizeBytes > 0)
                revision.sizeBytes else app.selectedDisplayStatsBytes
        }
    }

    suspend fun syncInstalledApps(userId: Int, apps: List<PackageEntity>) {
        dao.replaceInstalledApps(userId, apps.map { it.toBackupApp() })
    }

    suspend fun updateAppNote(packageName: String, userId: Int, note: String) {
        dao.updateAppNote(packageName, userId, note.trim())
        removeUnusedApps(userId)
    }

    suspend fun getAppNotes(): List<AppNoteItem> = dao.getAppsWithNotes().map {
        AppNoteItem(it.packageName, it.userId, it.note)
    }

    suspend fun importAppNotes(notes: List<AppNoteItem>) {
        require(notes.all { PathUtil.isValidPackageName(it.packageName) && it.userId >= 0 })
        dao.importAppNotes(notes)
    }

    suspend fun removeUnusedApps(userId: Int) {
        dao.deleteUninstalledAppsWithoutRevisions(userId)
    }

    suspend fun removeUnusedApps() {
        dao.deleteUninstalledAppsWithoutRevisions()
    }

    suspend fun recordLegacyRevision(
        restore: PackageEntity,
        contentMask: Int,
        sizeBytes: Long,
    ) = database.withTransaction {
        val index = restore.indexInfo
        val packages = database.packageDao()
        if (packages.query(restore.packageName, OpType.RESTORE, restore.userId, restore.preserveId, index.cloud, index.backupDir) == null) {
            packages.upsert(restore)
        }
        dao.recordRevision(
            restore.toBackupApp(isInstalled = false),
            restore.toRevision(
                repositoryId = "${restore.indexInfo.cloud}:${restore.indexInfo.backupDir}",
                contentMask = contentMask,
                sizeBytes = sizeBytes,
            ),
        )
    }

    suspend fun getLatestReusableLegacyRevision(app: PackageEntity, repositoryId: String): PackageEntity? {
        if (!app.apkSelected || !repositoryId.startsWith(':') || !context.readFastSameVersionBackup().first()) return null
        val revision = dao.getLatestApkRevision(app.packageName, app.userId, repositoryId) ?: return null
        val previous = findLegacyRevision(revision) ?: return null
        if (!app.canReuseApkFrom(previous)) return null
        val revisionDir = getLocalRevisionDir(revision) ?: return null
        val manifest = rootService.readJson<BackupManifest>(PathUtil.getBackupManifestDst(revisionDir)) ?: return null
        if (!manifest.matchesRevision(revision) || !manifest.hasValidFileEntries()) return null
        val file = manifest.files.orEmpty().firstOrNull { it.name == "apk.${app.indexInfo.compressionType.suffix}" } ?: return null
        val path = "$revisionDir/${file.name}"
        if (rootService.fileLength(path) != file.sizeBytes || rootService.calculateSHA256(path) != file.sha256) return null
        return previous
    }

    suspend fun getManifestFile(path: String): BackupManifestFile? {
        val size = rootService.fileLength(path) ?: return null
        val digest = rootService.calculateSHA256(path) ?: return null
        return BackupManifestFile(PathUtil.getFileName(path), size, digest)
    }

    suspend fun writeManifest(
        app: PackageEntity,
        createdAt: Long,
        revisionDir: String,
        note: String = "",
        knownFiles: List<BackupManifestFile> = emptyList(),
        titaniumSource: TitaniumSource? = null,
    ): BackupManifest? {
        val archives = DataType.packageArchives.filter { it.getSelected(app.dataStates) }
        if (archives.isEmpty()) return null
        val names = archives.map { "${it.type}.${app.indexInfo.compressionType.suffix}" } + ConfigsPackageRestoreName
        val known = knownFiles.associateBy(BackupManifestFile::name)
        val files = names.sorted().map { name -> known[name] ?: getManifestFile("$revisionDir/$name") ?: return null }
        val manifest = BackupManifest(
            packageName = app.packageName,
            userId = app.userId,
            createdAt = createdAt,
            versionName = app.packageInfo.versionName,
            versionCode = app.packageInfo.versionCode,
            contentMask = archiveContentMask(files.map { it.name }),
            note = note.trim(),
            files = files,
            titaniumSource = titaniumSource,
        )
        return manifest.takeIf { it.hasValidFileEntries() && rootService.writeJson(it, PathUtil.getBackupManifestDst(revisionDir)).isSuccess }
    }

    fun rememberVerification(result: VerificationResult) {
        if (result.status != BackupVerificationStatus.NOT_VERIFIED) {
            _verificationResults.update { it + (result.revision.id to result) }
        }
    }

    fun knownDamagedBackups(packages: List<PackageEntity>): List<PackageEntity> {
        val damaged = verificationResults.value.values.asSequence()
            .filter { it.status == BackupVerificationStatus.DAMAGED }
            .map { it.revision.repositoryId to it.revision.artifactId }.toSet()
        return packages.filter { ("${it.indexInfo.cloud}:${it.indexInfo.backupDir}" to it.archivesRelativeDir) in damaged }
    }

    suspend fun saveBackupIcon(packageName: String, appsDir: String, revisionDir: String): String? {
        appIconRepository.saveInstalledIcon(packageName, appsDir)?.let { return it }
        appIconRepository.repairFromBackup(revisionDir, appsDir, packageName, overwrite = true)
        return PathUtil.getAppIconPath(appsDir, packageName).takeIf { rootService.exists(it) }
    }

    suspend fun inspectRevision(revision: BackupRevisionEntity): VerificationResult = withContext(Dispatchers.IO) {
        val result = inspectRevisionFiles(revision) { manifest ->
            val files = manifest.files.orEmpty()
            val size = files.sumOf(BackupManifestFile::sizeBytes)
            if (revision.sizeBytes != size) dao.updateRevisionSize(revision.id, size)
            findLegacyRevision(revision)?.let { restore ->
                val stats = manifest.displayStats(restore.indexInfo.compressionType)
                if (restore.displayStats != stats) packageRepository.updateDisplayStats(restore.id, stats)
            }
        }
        val revisionDir = getLocalRevisionDir(revision)
        revisionDir?.let { refreshXposedModule(revision, it) }
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
        knownFiles: List<BackupManifestFile> = emptyList(),
    ) = archiveStats { type ->
        if (type.getSelected(states)) {
            val name = "${type.type}.${compression.suffix}"
            knownFiles.firstOrNull { it.name == name }?.sizeBytes ?: rootService.calculateSize("$revisionDir/$name")
        } else 0L
    }

    private fun BackupManifest.displayStats(compression: CompressionType) = archiveStats { type ->
        files.orEmpty().firstOrNull { it.name == "${type.type}.${compression.suffix}" }?.sizeBytes ?: 0L
    }

    private inline fun archiveStats(size: (DataType) -> Long) = PackageDataStats(
        apkBytes = size(DataType.PACKAGE_APK), userBytes = size(DataType.PACKAGE_USER),
        userDeBytes = size(DataType.PACKAGE_USER_DE), dataBytes = size(DataType.PACKAGE_DATA),
        obbBytes = size(DataType.PACKAGE_OBB), mediaBytes = size(DataType.PACKAGE_MEDIA),
    )

    private suspend fun refreshXposedModule(revision: BackupRevisionEntity, revisionDir: String) {
        val isXposedModule = detectXposedModule(revision.packageName, revision.userId, revisionDir)
            ?: return
        packageRepository.getPackage(revision.packageName, OpType.BACKUP, revision.userId)?.let { app ->
            if (app.packageInfo.isXposedModule != isXposedModule) {
                packageRepository.updateXposedModule(app.id, isXposedModule)
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

    private suspend fun inspectRevisionFiles(
        revision: BackupRevisionEntity,
        onValid: suspend (BackupManifest) -> Unit = {},
    ): VerificationResult {
        val appLabel = dao.getApp(revision.packageName, revision.userId)?.label ?: revision.packageName
        val revisionDir = getLocalRevisionDir(revision) ?: return VerificationResult(
            revision = revision,
            appLabel = appLabel,
            status = BackupVerificationStatus.NOT_VERIFIED,
            issues = listOf(VerificationIssue(VerificationIssueType.NOT_LOCAL)),
        )
        val issues = mutableListOf<VerificationIssue>()
        fun unreadable(path: String) = VerificationResult(
            revision, appLabel, BackupVerificationStatus.DAMAGED, listOf(VerificationIssue(VerificationIssueType.UNREADABLE, path)),
        )
        val files = try {
            rootService.readDirectory(revisionDir, includeFiles = true).files.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return unreadable(revisionDir)
        }

        suspend fun inspectFile(file: BackupManifestFile) {
            val path = "$revisionDir/${file.name}"
            if (path !in files) {
                issues += VerificationIssue(VerificationIssueType.FILE_MISSING, file.name)
                return
            }
            val actual = getManifestFile(path)
            when {
                actual == null -> issues += VerificationIssue(VerificationIssueType.UNREADABLE, file.name)
                actual.sizeBytes != file.sizeBytes ->
                    issues += VerificationIssue(VerificationIssueType.FILE_SIZE_MISMATCH, file.name)
                actual.sha256 != file.sha256 ->
                    issues += VerificationIssue(VerificationIssueType.FILE_CHECKSUM_MISMATCH, file.name)
            }
        }

        val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
        if (manifestPath !in files) return VerificationResult(
            revision, appLabel, BackupVerificationStatus.DAMAGED, listOf(VerificationIssue(VerificationIssueType.MANIFEST_MISSING)),
        )
        val json = try {
            rootService.readTextChecked(manifestPath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return unreadable(manifestPath)
        }
        val manifest = runCatching { rootService.gsonUtil.fromJson<BackupManifest?>(json, BackupManifest::class.java) }.getOrNull()
        if (manifest != null && manifest.schemaVersion != BACKUP_MANIFEST_SCHEMA_VERSION) return VerificationResult(
            revision, appLabel, BackupVerificationStatus.DAMAGED, listOf(VerificationIssue(VerificationIssueType.METADATA_MISMATCH)),
        )
        if (manifest == null || !manifest.hasValidFileEntries()) return VerificationResult(
            revision, appLabel, BackupVerificationStatus.DAMAGED, listOf(VerificationIssue(VerificationIssueType.MANIFEST_INVALID)),
        )
        if (!manifest.matchesRevision(revision)) issues += VerificationIssue(VerificationIssueType.METADATA_MISMATCH)
        manifest.files.orEmpty().forEach { inspectFile(it) }
        if (issues.isEmpty()) {
            val path = PathUtil.getPackageRestoreConfigDst(revisionDir)
            val config = try {
                readIndexMetadata(path, PackageRestoreConfig::class.java)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return unreadable(path)
            }
            if (manifest.restorePackage(config, revision.repositoryId.substringAfter(':')) == null) {
                issues += VerificationIssue(VerificationIssueType.METADATA_MISMATCH, ConfigsPackageRestoreName)
            } else onValid(manifest)
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
        val current = dao.getRevisions(":${context.localBackupSaveDir()}").associateBy { it.id }
        val remaining = results.filter { it.revision.id in current }.associateByTo(linkedMapOf()) { it.revision.id }
        val failed = remaining.values.filter { it.canClean }
        var deletedCount = 0
        var failedCount = 0
        onProgress(0, failed.size)
        failed.forEachIndexed { index, result ->
            val revision = current.getValue(result.revision.id)
            val checked = inspectRevisionFiles(revision)
            rememberVerification(checked)
            remaining[revision.id] = checked
            if (checked.canClean) {
                if (deleteRevision(revision)) {
                    remaining.remove(revision.id)
                    deletedCount++
                } else failedCount++
            }
            onProgress(index + 1, failed.size)
        }
        return CleanupReport(remaining.values.toList(), deletedCount, failedCount)
    }

    suspend fun rebuildLocalIndex(backupDir: String = context.localBackupSaveDir(), onProgress: suspend (Int, Int, Int) -> Unit): List<RebuildResult> {
        val repositoryId = ":$backupDir"
        val original = dao.getRevisions(repositoryId)
        check(rootService.existsChecked(backupDir)) { "Cannot read directory: $backupDir" }
        val appsDir = "$backupDir/${PathUtil.getAppsRelativeDir()}"
        val rebuilt = mutableListOf<Pair<PackageEntity, BackupManifest>>()
        val revisionDirs = buildList {
            if (rootService.existsChecked(appsDir)) {
                rootService.readDirectory(appsDir, includeFiles = false).directories.forEach { packageDir ->
                    addAll(rootService.readDirectory(packageDir, includeFiles = false).directories)
                }
            }
        }
        onProgress(0, revisionDirs.size, 0)
        revisionDirs.forEachIndexed { index, revisionDir ->
            val config = readIndexMetadata(PathUtil.getPackageRestoreConfigDst(revisionDir), PackageRestoreConfig::class.java)
            val manifest = readIndexMetadata(PathUtil.getBackupManifestDst(revisionDir), BackupManifest::class.java)
            val app = manifest?.restorePackage(config, backupDir)?.takeIf {
                PathUtil.getPackageArchiveRelativeDir(it.packageName, it.userId, it.preserveId)
                    ?.let { relative -> FileUtil.normalizeAbsolutePath("$appsDir/$relative") } == FileUtil.normalizeAbsolutePath(revisionDir)
            }
            if (app != null && manifest != null) {
                app.displayStats = manifest.displayStats(app.indexInfo.compressionType)
                detectXposedModule(app.packageName, app.userId, revisionDir)?.let {
                    app.packageInfo.isXposedModule = it
                }
                rebuilt += app to manifest
            }
            onProgress(index + 1, revisionDirs.size, rebuilt.size)
        }
        val revisions = rebuilt.map { (app, manifest) ->
            app.toRevision(
                repositoryId = repositoryId,
                contentMask = manifest.contentMask,
                sizeBytes = manifest.files.orEmpty().sumOf(BackupManifestFile::sizeBytes),
                note = manifest.note.orEmpty(),
            )
        }
        revisionMutex.withLock {
            database.withTransaction {
                check(dao.getRevisions(repositoryId) == original) { context.getString(R.string.rebuild_source_changed) }
                database.packageDao().replaceLocalRestoreIndex(backupDir, rebuilt.map { it.first })
                val apps = rebuilt.sortedByDescending { (app, _) -> app.preserveId }
                    .distinctBy { (app, _) -> app.packageName to app.userId }
                    .map { (app, _) ->
                        dao.getApp(app.packageName, app.userId)?.takeIf { it.isInstalled } ?: app.toBackupApp(isInstalled = false)
                    }
                dao.replaceRepositoryIndex(repositoryId, apps, revisions)
                dao.deleteUninstalledAppsWithoutRevisions()
            }
        }
        _verificationResults.update { results -> results.filterValues { it.revision.repositoryId != repositoryId } }
        return rebuilt.zip(revisions) { (app, _), revision -> RebuildResult(app.packageInfo.label, revision) }
    }

    private fun BackupManifest.restorePackage(config: PackageRestoreConfig?, backupDir: String): PackageEntity? = runCatching {
        if (config == null || config.schemaVersion != PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION ||
            schemaVersion != BACKUP_MANIFEST_SCHEMA_VERSION || !hasValidFileEntries() ||
            packageName != config.packageName || userId != config.userId || createdAt != config.createdAt ||
            versionName != config.versionName || versionCode != config.versionCode
        ) return null
        val app = config.toPackageEntity("", backupDir)
        val names = files.orEmpty().mapTo(mutableSetOf()) { it.name }
        app.takeIf {
            app.selectionFlag == contentMask && DataType.packageArchives.all { type ->
                !type.getSelected(app.dataStates) || "${type.type}.${config.compressionType.suffix}" in names
            }
        }
    }.getOrNull()

    private suspend fun <T> readIndexMetadata(path: String, type: Class<T>): T? {
        val text = rootService.readTextIfPresent(path) ?: return null
        return try {
            rootService.gsonUtil.fromJson(text, type)
        } catch (_: JsonParseException) {
            null
        }
    }

    enum class RetentionResult { DELETED, SKIPPED, FAILED }

    suspend fun deleteOldRevision(id: String, keep: Int): RetentionResult = revisionMutex.withLock {
        require(keep >= 1)
        val revision = dao.getOldRevision(id, ":${context.localBackupSaveDir()}", keep)
        when {
            revision == null || revision.isProtectedByNote() -> RetentionResult.SKIPPED
            removeRevision(revision) -> RetentionResult.DELETED
            else -> RetentionResult.FAILED
        }
    }

    suspend fun deleteRevision(revision: BackupRevisionEntity): Boolean = revisionMutex.withLock { removeRevision(revision) }

    private suspend fun removeRevision(revision: BackupRevisionEntity): Boolean {
        if (!hasValidArtifactId(revision) || !dao.hasRevision(revision.id)) return false
        val packageEntity = findLegacyRevision(revision)
        val deleted = if (packageEntity != null) {
            packageRepository.delete(packageEntity)
            packageRepository.getPackage(packageEntity.id) == null
        } else {
            val revisionDir = getLocalRevisionDir(revision) ?: return false
            rootService.deleteRecursively(revisionDir)
        }
        if (!deleted) return false
        dao.deleteRevisionAndSync(revision)
        _verificationResults.update { it - revision.id }
        getLocalRevisionDir(revision)?.let { revisionDir ->
            val packageDir = PathUtil.getParentPath(revisionDir)
            val iconPath = PathUtil.getAppIconPath(PathUtil.getParentPath(packageDir), revision.packageName)
            val remaining = runCatching { rootService.readDirectory(packageDir, includeFiles = true) }.getOrNull()
            if (remaining != null && remaining.directories.isEmpty() && remaining.files.all { it == iconPath }) {
                rootService.removeEntry(iconPath)
                rootService.removeEntry(packageDir, directory = true)
            }
        }
        dao.deleteUninstalledAppsWithoutRevisions(revision.userId)
        return true
    }

    suspend fun getLocalRevisionCount(): Int = dao.countRevisions(":${context.localBackupSaveDir()}")

    suspend fun getLocalRetentionCandidates(retainCount: Int): List<RetentionCandidate> {
        val labels = dao.getRevisionLabels(":${context.localBackupSaveDir()}")
        return findRetentionRevisions(labels.keys, retainCount).map {
            RetentionCandidate(labels.getValue(it.revision), it.revision, it.removable)
        }
    }

    suspend fun updateRevisionNote(revision: BackupRevisionEntity, note: String): Boolean = withContext(Dispatchers.IO) {
        revisionMutex.withLock {
            if (!dao.hasRevision(revision.id)) return@withLock false
            val normalized = note.trim()
            val revisionDir = getLocalRevisionDir(revision) ?: return@withLock false
            val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
            val manifest = rootService.readJson<BackupManifest>(manifestPath) ?: return@withLock false
            if (!rootService.writeJson(manifest.copy(note = normalized), manifestPath).isSuccess) return@withLock false
            dao.updateRevisionNote(revision.id, normalized)
            true
        }
    }

    suspend fun selectRevisionForRestore(revision: BackupRevisionEntity, dataStates: PackageDataStates): RestoreSelection? = database.withTransaction {
        val packageEntity = findLegacyRevision(revision) ?: return@withTransaction null
        selectRestoreItems(listOf(PackageDataStatesEntity(packageEntity.id, dataStates)))
        RestoreSelection(
            cloudName = packageEntity.indexInfo.cloud,
            backupDir = packageEntity.indexInfo.backupDir,
        )
    }

    suspend fun selectLatestLocalRevisionsForRestore(keys: Set<AppKey>): RestoreSelection? {
        val backupDir = context.localBackupSaveDir()
        val repositoryId = ":$backupDir"
        return database.withTransaction {
            val latest = dao.getRevisions(repositoryId)
                .filter { AppKey(it.packageName, it.userId) in keys }
                .distinctBy { AppKey(it.packageName, it.userId) }
            val items = latest.mapNotNull { revision ->
                findLegacyRevision(revision)?.let { PackageDataStatesEntity(it.id, revision.contentMask.toDataStates()) }
            }
            if (items.isEmpty()) return@withTransaction null
            selectRestoreItems(items)
            RestoreSelection(cloudName = "", backupDir = backupDir)
        }
    }

    private suspend fun selectRestoreItems(items: List<PackageDataStatesEntity>) {
        val packages = database.packageDao()
        packages.clearActivated(OpType.RESTORE)
        packages.updatePackageDataStates(items)
        items.map { it.id }.chunked(SQLITE_BIND_LIMIT - 1).forEach { packages.activateByIds(it, true) }
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

    private fun Int.toDataStates() = PackageDataStates(
        apkState = if (this and 1 != 0) DataState.Selected else DataState.NotSelected,
        userState = if (this and 2 != 0) DataState.Selected else DataState.NotSelected,
        userDeState = if (this and 4 != 0) DataState.Selected else DataState.NotSelected,
        dataState = if (this and 8 != 0) DataState.Selected else DataState.NotSelected,
        obbState = if (this and 16 != 0) DataState.Selected else DataState.NotSelected,
        mediaState = if (this and 32 != 0) DataState.Selected else DataState.NotSelected,
    )

    private fun PackageEntity.toBackupApp(isInstalled: Boolean = true) = BackupAppEntity(
        packageName = packageName,
        userId = userId,
        label = packageInfo.label,
        versionName = packageInfo.versionName,
        versionCode = packageInfo.versionCode,
        firstInstallTime = packageInfo.firstInstallTime,
        lastUpdateTime = packageInfo.lastUpdateTime,
        isSystem = isSystemApp,
        isInstalled = isInstalled,
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
