package com.xayah.core.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.JsonParseException
import com.xayah.core.data.R
import com.xayah.core.database.AppDatabase
import com.xayah.core.database.SQLITE_BIND_LIMIT
import com.xayah.core.datastore.readFastSameVersionBackup
import com.xayah.core.datastore.readBackupSavePath
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
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.TitaniumSource
import com.xayah.core.model.database.PackageDataStates
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

    internal fun observeBackedUpApps() = dao.observeBackedUpApps()

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
        saveAppNotes(listOf(AppNoteItem(packageName, userId, note)))
    }

    suspend fun getAppNotes(): List<AppNoteItem> = dao.getAppsWithNotes().map {
        AppNoteItem(it.packageName, it.userId, it.note)
    }

    suspend fun saveAppNotes(notes: List<AppNoteItem>) {
        require(notes.all { PathUtil.isValidPackageName(it.packageName) && it.userId >= 0 })
        dao.saveAppNotes(notes)
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

    suspend fun findReusableApkArchive(app: PackageEntity, repositoryId: String, apkEntries: Set<String>): String? {
        if (!app.apkSelected || !repositoryId.startsWith(':') || !context.readFastSameVersionBackup().first()) return null
        val revision = dao.getLatestApkRevision(app.packageName, app.userId, repositoryId) ?: return null
        if (app.packageInfo.versionName != revision.appVersionName || app.packageInfo.versionCode != revision.appVersionCode) return null
        val revisionDir = getLocalRevisionDir(revision) ?: return null
        val manifest = rootService.readJson<BackupManifest>(PathUtil.getBackupManifestDst(revisionDir)) ?: return null
        if (manifest.apkEntries != apkEntries || !manifest.matchesRevision(revision) || !manifest.hasValidFileEntries()) return null
        val file = manifest.files.orEmpty().firstOrNull { it.name == "apk.${app.indexInfo.compressionType.suffix}" } ?: return null
        val path = "$revisionDir/${file.name}"
        if (rootService.fileLength(path) != file.sizeBytes || rootService.calculateSHA256(path) != file.sha256) return null
        return path
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
        apkEntries: Set<String>? = null,
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
            contentMask = app.selectionFlag,
            note = note.trim(),
            files = files,
            titaniumSource = titaniumSource,
            apkEntries = apkEntries?.takeIf { app.apkSelected },
        )
        return manifest.takeIf { it.hasValidFileEntries() && rootService.writeJson(it, PathUtil.getBackupManifestDst(revisionDir)).isSuccess }
    }

    private suspend fun rememberVerification(result: VerificationResult) {
        if (result.status == BackupVerificationStatus.NOT_VERIFIED) return
        revisionMutex.withLock {
            if (dao.hasRevision(result.revision.id)) cacheVerification(result)
        }
    }

    private fun cacheVerification(result: VerificationResult) {
        _verificationResults.update { it + (result.revision.id to result) }
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
        if (getLocalRevisionDir(revision) != null) rootService.requireBackupStorage(context, revision.repositoryId.substringAfter(':'))
        verifyRevision(revision)
    }

    private suspend fun verifyRevision(revision: BackupRevisionEntity): VerificationResult {
        val result = inspectRevisionFiles(revision) { manifest ->
            val files = manifest.files.orEmpty()
            val size = files.sumOf(BackupManifestFile::sizeBytes)
            if (revision.sizeBytes != size) dao.updateRevisionSize(revision.id, size)
            findLegacyRevision(revision)?.let { restore ->
                val stats = manifest.displayStats(restore.indexInfo.compressionType)
                if (restore.displayStats != stats) packageRepository.updateDisplayStats(restore.id, stats)
            }
        }
        if (result.issues.any { it.type == VerificationIssueType.UNREADABLE }) {
            rootService.requireBackupStorage(context, revision.repositoryId.substringAfter(':'))
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
        return result.copy(iconRepaired = iconRepaired).also { rememberVerification(it) }
    }

    internal suspend fun calculateDisplayStats(
        revisionDir: String,
        compression: CompressionType,
        states: PackageDataStates? = null,
        knownFiles: List<BackupManifestFile> = emptyList(),
    ) = archiveStats { type ->
        if (states == null || type.getSelected(states)) {
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

    private suspend fun detectXposedModule(packageName: String, userId: Int, revisionDir: String, apk: String? = null): Boolean? =
        xposedModuleDetector.isInstalledModule(packageName, userId)
            ?: if (apk == null) xposedModuleDetector.isModuleInBackup(revisionDir) else xposedModuleDetector.isModule(apk)

    private suspend fun inspectRevisionFiles(
        revision: BackupRevisionEntity,
        forCleanup: Boolean = false,
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
        suspend fun inspectFile(file: BackupManifestFile) {
            val path = "$revisionDir/${file.name}"
            val issue = try {
                val size = rootService.fileLengthIfPresent(path)
                if (size == null) VerificationIssueType.FILE_MISSING else {
                    val digest = rootService.calculateSHA256(path)
                    when {
                        digest == null -> VerificationIssueType.UNREADABLE
                        size != file.sizeBytes -> VerificationIssueType.FILE_SIZE_MISMATCH
                        digest != file.sha256 -> VerificationIssueType.FILE_CHECKSUM_MISMATCH
                        else -> null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                VerificationIssueType.UNREADABLE
            }
            if (issue != null) issues += VerificationIssue(issue, file.name)
        }

        val manifestPath = PathUtil.getBackupManifestDst(revisionDir)
        val json = try {
            val text = rootService.readTextIfPresent(manifestPath)
            if (text == null || forCleanup) rootService.readDirectory(revisionDir, includeFiles = false)
            text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return unreadable(manifestPath)
        } ?: return VerificationResult(
            revision, appLabel, BackupVerificationStatus.DAMAGED, listOf(VerificationIssue(VerificationIssueType.MANIFEST_MISSING)),
        )
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

    suspend fun verifyAllLocal(onProgress: suspend (Int, Int) -> Unit): List<VerificationResult> {
        val backupDir = context.localBackupSaveDir()
        val revisions = dao.getRevisions(":$backupDir")
        if (revisions.isNotEmpty()) rootService.requireBackupStorage(context, backupDir)
        onProgress(0, revisions.size)
        return revisions.mapIndexed { index, revision ->
            verifyRevision(revision).also { onProgress(index + 1, revisions.size) }
        }
    }

    suspend fun deleteFailedLocalBackups(
        results: List<VerificationResult>,
        onProgress: suspend (Int, Int) -> Unit,
    ): CleanupReport {
        val backupDir = context.localBackupSaveDir()
        rootService.requireBackupStorage(context, backupDir)
        val current = dao.getRevisions(":$backupDir").associateBy { it.id }
        val remaining = results.filter { it.revision.id in current }.associateByTo(linkedMapOf()) { it.revision.id }
        val failed = remaining.values.filter { it.canClean }
        var deletedCount = 0
        var failedCount = 0
        onProgress(0, failed.size)
        failed.forEachIndexed { index, result ->
            val revision = current.getValue(result.revision.id)
            revisionMutex.withLock {
                if (!dao.hasRevision(revision.id)) {
                    remaining.remove(revision.id)
                    return@withLock
                }
                val checked = inspectRevisionFiles(revision, forCleanup = true)
                if (checked.canClean && removeRevision(revision)) {
                    remaining.remove(revision.id)
                    deletedCount++
                } else {
                    if (checked.canClean) failedCount++
                    remaining[revision.id] = checked
                    cacheVerification(checked)
                }
            }
            onProgress(index + 1, failed.size)
        }
        return CleanupReport(remaining.values.toList(), deletedCount, failedCount)
    }

    suspend fun rebuildLocalIndex(backupDir: String = context.localBackupSaveDir(), onProgress: suspend (Int, Int, Int) -> Unit): List<RebuildResult> {
        val repositoryId = ":$backupDir"
        val original = dao.getRevisions(repositoryId)
        rootService.requireBackupStorage(context, backupDir)
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
            readLocalRevision(backupDir, revisionDir)?.let(rebuilt::add)
            onProgress(index + 1, revisionDirs.size, rebuilt.size)
        }
        val originalByArtifact = original.associateBy { it.artifactId }
        val revisions = rebuilt.map { (app, manifest) ->
            val revision = app.toRevision(repositoryId, manifest)
            originalByArtifact[revision.artifactId]?.takeIf(manifest::matchesRevision)?.let { revision.copy(id = it.id) } ?: revision
        }
        revisionMutex.withLock {
            rootService.requireBackupStorage(context, backupDir)
            database.withTransaction {
                check(dao.getRevisions(repositoryId) == original) { context.getString(R.string.rebuild_source_changed) }
                database.packageDao().replaceLocalRestoreIndex(backupDir, rebuilt.map { it.first })
                val apps = rebuilt.sortedByDescending { (app, _) -> app.preserveId }
                    .distinctBy { (app, _) -> app.packageName to app.userId }
                    .map { (app, _) -> app.toBackupApp(isInstalled = false) }
                dao.replaceRepositoryIndex(repositoryId, apps, revisions)
                refreshArchivedMetadata((original + revisions).map { AppKey(it.packageName, it.userId) })
                dao.deleteUninstalledAppsWithoutRevisions()
            }
            _verificationResults.update { results -> results.filterValues { it.revision.repositoryId != repositoryId } }
        }
        return rebuilt.zip(revisions) { (app, _), revision -> RebuildResult(app.packageInfo.label, revision) }
    }

    internal suspend fun isLocalRevisionIndexed(backupDir: String, artifactId: String): Boolean {
        val revision = dao.getRevisions(":$backupDir", listOf(artifactId)).singleOrNull() ?: return false
        return findLegacyRevision(revision) != null
    }

    internal suspend fun indexImportedRevision(
        backupDir: String, dir: String, apk: String? = null, publish: suspend () -> Unit = {},
    ): Boolean = revisionMutex.withLock {
        publish()
        val (app, manifest) = checkNotNull(readLocalRevision(backupDir, dir, apk)) { "Invalid backup metadata: $dir" }
        val revision = app.toRevision(":$backupDir", manifest)
        val (previousId, added) = database.withTransaction {
            val packages = database.packageDao()
            val stored = packages.query(app.packageName, OpType.RESTORE, app.userId, app.preserveId, "", backupDir)
            val previous = dao.getRevisions(revision.repositoryId, listOf(revision.artifactId)).singleOrNull()
            dao.insertMissingApp(app.toBackupApp(isInstalled = false))
            packages.upsert(app.withRestoreState(stored))
            dao.upsertRevisions(listOf(previous?.let { revision.copy(id = it.id) } ?: revision))
            dao.syncLastBackupTime(app.packageName, app.userId, OpType.BACKUP)
            refreshArchivedMetadata(listOf(AppKey(app.packageName, app.userId)))
            previous?.id to (previous == null || stored == null)
        }
        if (previousId != null) _verificationResults.update { it - previousId }
        added
    }

    private suspend fun readLocalRevision(backupDir: String, dir: String, apk: String? = null): Pair<PackageEntity, BackupManifest>? {
        val config = readIndexMetadata(PathUtil.getPackageRestoreConfigDst(dir), PackageRestoreConfig::class.java)
        val manifest = readIndexMetadata(PathUtil.getBackupManifestDst(dir), BackupManifest::class.java) ?: return null
        val app = manifest.restorePackage(config, backupDir) ?: return null
        val relative = PathUtil.getPackageArchiveRelativeDir(app.packageName, app.userId, app.preserveId) ?: return null
        if (FileUtil.normalizeAbsolutePath("$backupDir/${PathUtil.getAppsRelativeDir()}/$relative") != FileUtil.normalizeAbsolutePath(dir)) return null
        app.displayStats = manifest.displayStats(app.indexInfo.compressionType)
        detectXposedModule(app.packageName, app.userId, dir, apk)?.let { app.packageInfo.isXposedModule = it }
        return app to manifest
    }

    private fun PackageEntity.toRevision(repositoryId: String, manifest: BackupManifest) = toRevision(
        repositoryId, manifest.contentMask, manifest.files.orEmpty().sumOf(BackupManifestFile::sizeBytes), manifest.note.orEmpty(),
    )

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

    suspend fun deleteBackup(app: PackageEntity): Boolean = revisionMutex.withLock {
        if (app.indexInfo.opType != OpType.RESTORE) return@withLock false
        val repositoryId = "${app.indexInfo.cloud}:${app.indexInfo.backupDir}"
        val revision = dao.getRevisions(repositoryId, listOf(app.archivesRelativeDir)).singleOrNull()
        if (revision == null) packageRepository.delete(app) else removeRevision(revision)
    }

    private suspend fun removeRevision(revision: BackupRevisionEntity): Boolean {
        if (!hasValidArtifactId(revision) || !dao.hasRevision(revision.id)) return false
        val packageEntity = findLegacyRevision(revision)
        val deleted = if (packageEntity != null) {
            packageRepository.delete(packageEntity)
        } else {
            val revisionDir = getLocalRevisionDir(revision) ?: return false
            if (!isBackupVolumeMounted(revisionDir)) return false
            rootService.deleteRecursively(revisionDir)
        }
        if (!deleted) return false
        database.withTransaction {
            dao.deleteRevisionAndSync(revision)
            refreshArchivedMetadata(listOf(AppKey(revision.packageName, revision.userId)))
            dao.deleteUninstalledAppsWithoutRevisions(revision.userId)
        }
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
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLocalRevisionCount(): Flow<Int> = context.readBackupSavePath().distinctUntilChanged()
        .flatMapLatest { dao.observeRevisionCount(":$it") }

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
            if (!manifest.matchesRevision(revision) || !manifest.hasValidFileEntries()) return@withLock false
            if (!rootService.writeJson(manifest.copy(note = normalized), manifestPath).isSuccess) return@withLock false
            dao.updateRevisionNote(revision.id, normalized)
            true
        }
    }

    suspend fun revisionForRestore(revision: BackupRevisionEntity, dataStates: PackageDataStates): PackageEntity? =
        findLegacyRevision(revision)?.copy(dataStates = dataStates.copy())

    suspend fun latestLocalPackagesForRestore(keys: Set<AppKey>): List<PackageEntity> {
        val repositoryId = ":${context.localBackupSaveDir()}"
        return database.withTransaction {
            dao.getRevisions(repositoryId)
                .distinctBy { AppKey(it.packageName, it.userId) }
                .filter { AppKey(it.packageName, it.userId) in keys }
                .mapNotNull { revision -> revisionForRestore(revision, PackageDataStates.fromArchiveMask(revision.contentMask)) }
        }
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

    private suspend fun refreshArchivedMetadata(keys: List<AppKey>) {
        val apps = keys.distinct().groupBy(AppKey::userId).flatMap { (userId, group) ->
            group.map(AppKey::packageName).chunked(SQLITE_BIND_LIMIT - 2).flatMap { names ->
                dao.latestArchivedApps(userId, names, OpType.RESTORE)
            }
        }
        dao.upsertApps(apps)
    }

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
