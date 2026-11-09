package com.xayah.core.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.xayah.core.data.R
import com.xayah.core.data.util.archiveExclusions
import com.xayah.core.model.BackupManifest
import com.xayah.core.model.BackupManifestFile
import com.xayah.core.model.BACKUP_MANIFEST_SCHEMA_VERSION
import com.xayah.core.model.hasValidFileEntries
import com.xayah.core.model.CompressionType
import com.xayah.core.model.ConfigsPackageRestoreName
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.TitaniumSource
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.toRestoreConfig
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readCompressionType
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.shellQuote
import com.xayah.core.util.FileUtil
import com.xayah.core.util.BackupManifestName
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Singleton
class TitaniumImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val pathUtil: PathUtil,
    private val appBackupRepository: AppBackupRepository,
    private val appIconRepository: AppIconRepository,
    private val labelsRepo: LabelsRepo,
) {
    enum class Status { IMPORTED, PARTIAL, SKIPPED, FAILED }

    data class BackupResult(
        val packageName: String,
        val label: String,
        val versionName: String,
        val createdAt: Long,
        val status: Status,
        val detail: String = "",
        val skippedEntries: Int = 0,
    )

    data class LabelResult(
        val label: String,
        val color: Long,
        val appCount: Int,
        val status: Status,
        val detail: String = "",
    )

    data class BackupCandidate(
        val metadataPath: String,
        val backupDir: String,
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val createdAt: Long,
        val apkMd5: String?,
        val hasData: Boolean,
        val imported: Boolean,
        val missingFiles: List<String>,
        val iconPath: String?,
        val scanError: String? = null,
    ) {
        val id: String get() = metadataPath
        val hasApk: Boolean get() = apkMd5 != null
        val source: TitaniumSource get() = TitaniumSource(apkMd5?.lowercase(Locale.ROOT), hasData)
        val isComplete: Boolean get() = scanError == null && missingFiles.isEmpty() && (hasApk || hasData)

        internal fun matches(manifest: BackupManifest): Boolean {
            if (manifest.packageName != packageName || manifest.userId != 0 || manifest.createdAt != createdAt ||
                manifest.versionName != versionName || manifest.versionCode != versionCode || manifest.contentMask == 0 ||
                (manifest.contentMask and 1 != 0) != hasApk || (!hasData && manifest.contentMask != 1)
            ) return false
            manifest.titaniumSource?.let { return it == source }
            val firstLine = manifest.note?.lineSequence()?.firstOrNull()
            return firstLine == IMPORT_NOTE || firstLine == "$IMPORT_NOTE (protected)"
        }
    }

    data class LabelCandidate(
        val name: String,
        val color: Long,
        val packages: List<String>,
        val imported: Boolean,
    )

    private data class ImportedData(
        val types: Set<DataType>,
        val skippedEntries: Int,
    )

    private val backupImportMutex = Mutex()

    companion object {
        const val DEFAULT_BACKUP_DIR = "/storage/emulated/0/TitaniumBackup"
        const val DEFAULT_LABEL_DB = "/storage/emulated/0/data/com.keramidas.TitaniumBackup/settings/databases~custom"
        const val IMPORT_NOTE = "from Titanium Backup"
        private val metadataPattern = Regex("^(.+)-(\\d{8})-(\\d{6})\\.properties$")
        private val apkChecksumPattern = Regex("[0-9a-fA-F]{32}")
    }

    suspend fun detectBackupDir(): String? = DEFAULT_BACKUP_DIR.takeIf { rootService.exists(it) }

    suspend fun detectLabelDatabase(): String? = DEFAULT_LABEL_DB.takeIf { rootService.exists(it) }

    private val previewRoot by lazy {
        runCatching {
            val data = context.dataDir
            val appDir = File(data.parentFile!!.canonicalFile, data.name)
            File(appDir, "${context.cacheDir.name}/titanium-preview")
        }.getOrNull()?.takeIf { FileUtil.deleteRecursively(it.path) }
    }

    fun clearPreviewCache(id: UUID) {
        previewRoot?.let { FileUtil.deleteRecursively(File(it, id.toString()).path) }
    }

    suspend fun scanBackups(
        sourceDir: String,
        previewId: UUID,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ): List<BackupCandidate> {
        val previewDir = previewRoot?.resolve(previewId.toString())?.takeIf { FileUtil.deleteRecursively(it.path) }
        val metadataFiles = rootService.readDirectory(sourceDir, includeFiles = true, strict = false).files
            .filter { metadataPattern.matches(PathUtil.getFileName(it)) }
            .sorted()
        val backupDir = context.localBackupSaveDir()
        onProgress(0, metadataFiles.size)
        return metadataFiles.mapIndexed { index, path ->
            coroutineContext.ensureActive()
            try {
                scanBackup(sourceDir, path, previewDir, backupDir)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val name = PathUtil.getFileName(path)
                val packageName = metadataPattern.matchEntire(name)!!.groupValues[1]
                BackupCandidate(
                    metadataPath = path,
                    backupDir = backupDir,
                    packageName = packageName,
                    label = packageName,
                    versionName = "",
                    versionCode = 0,
                    createdAt = 0,
                    apkMd5 = null,
                    hasData = false,
                    imported = false,
                    missingFiles = emptyList(),
                    iconPath = null,
                    scanError = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                )
            }.also { onProgress(index + 1, metadataFiles.size) }
        }
    }

    suspend fun scanLabels(databasePath: String): List<LabelCandidate> {
        val localDb = File.createTempFile("titanium-labels-", ".db", context.cacheDir)
        return try {
            check(rootService.copyTo(databasePath, localDb.path, overwrite = true)) { "Unable to read the Titanium Backup label database" }
            val labels = labelsRepo.getLabels().mapTo(mutableSetOf(), LabelEntity::label)
            val colors = labelsRepo.getLabelColors()
            val refs = labelsRepo.getAppRefs()
                .filter { it.userId == 0 }
                .groupBy { it.label }
                .mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.packageName } }
            readLabels(localDb).map { group ->
                LabelCandidate(
                    name = group.name,
                    color = group.color,
                    packages = group.packages.toList(),
                    imported = group.name in labels && colors[group.name] == group.color &&
                        refs[group.name].orEmpty().containsAll(group.packages),
                )
            }
        } finally {
            localDb.delete()
        }
    }

    suspend fun importBackups(
        candidates: List<BackupCandidate>,
        onProgress: suspend (completed: Int, total: Int, result: BackupResult?) -> Unit,
    ) = backupImportMutex.withLock {
        onProgress(0, candidates.size, null)
        val backupDir = candidates.firstOrNull()?.backupDir ?: return@withLock
        check(candidates.all { it.backupDir == backupDir } && context.localBackupSaveDir() == backupDir) {
            context.getString(R.string.import_destination_changed)
        }
        candidates.forEachIndexed { index, candidate ->
            coroutineContext.ensureActive()
            rootService.requireBackupStorage(context, backupDir, allowNewDirectory = true)
            val result = try {
                importBackup(candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                BackupResult(
                    packageName = candidate.packageName,
                    label = candidate.label,
                    versionName = candidate.versionName,
                    createdAt = candidate.createdAt,
                    status = Status.FAILED,
                    detail = error.message.orEmpty().ifBlank { error.javaClass.simpleName },
                )
            }
            onProgress(index + 1, candidates.size, result)
        }
    }

    suspend fun importLabels(
        candidates: List<LabelCandidate>,
        onProgress: suspend (completed: Int, total: Int, result: LabelResult?) -> Unit,
    ) {
        onProgress(0, candidates.size, null)
        candidates.forEachIndexed { index, group ->
            coroutineContext.ensureActive()
            val result = try {
                withContext(NonCancellable) {
                    val invalid = group.packages.firstOrNull { !PathUtil.isValidPackageName(it) }
                    require(invalid == null) { "Invalid package name: $invalid" }
                    labelsRepo.addLabels(listOf(LabelEntity(group.name)))
                    labelsRepo.setLabelColor(group.name, group.color)
                    labelsRepo.addLabelAppCrossRefs(group.packages.map { LabelAppCrossRefEntity(group.name, it, 0) })
                    LabelResult(group.name, group.color, group.packages.size, Status.IMPORTED)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LabelResult(group.name, group.color, group.packages.size, Status.FAILED, error.message.orEmpty().ifBlank { error.javaClass.simpleName })
            }
            onProgress(index + 1, candidates.size, result)
        }
    }

    private suspend fun scanBackup(sourceDir: String, metadataPath: String, previewDir: File?, backupDir: String): BackupCandidate {
        val fileName = PathUtil.getFileName(metadataPath)
        val match = checkNotNull(metadataPattern.matchEntire(fileName))
        val packageName = match.groupValues[1]
        check(PathUtil.isValidPackageName(packageName)) { "Invalid package name" }
        val createdAt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { isLenient = false }
            .parse("${match.groupValues[2]}-${match.groupValues[3]}")?.time
            ?: error("Invalid backup timestamp")
        val properties = readProperties(metadataPath)
        val label = properties.getProperty("app_label")
            ?: properties.getProperty("app_gui_label")
            ?: packageName
        val apkMd5 = properties.apkChecksum()
        val apkName = apkMd5?.let { "$packageName-$it.apk.gz" }
        val apkPath = apkName?.let { "$sourceDir/$it" }
        val dataName = fileName.removeSuffix(".properties") + ".tar.gz"
        val dataPath = "$sourceDir/$dataName"
        val hasApk = apkPath != null && (rootService.fileLengthIfPresent(apkPath) ?: 0L) > 0
        val dataSize = rootService.fileLengthIfPresent(dataPath)
        val hasData = (dataSize ?: 0L) > 0
        val missing = buildList {
            if (apkName != null && !hasApk) add(apkName)
            if (!hasData && (dataSize != null || apkName == null)) add(dataName)
        }
        val destination = "${pathUtil.getLocalBackupAppsDir(backupDir)}/$packageName/user_0@$createdAt"
        val candidate = BackupCandidate(
            metadataPath = metadataPath,
            backupDir = backupDir,
            packageName = packageName,
            label = label,
            versionName = properties.getProperty("app_version_name").orEmpty(),
            versionCode = properties.getProperty("app_version_code")?.toLongOrNull() ?: 0,
            createdAt = createdAt,
            apkMd5 = apkMd5,
            hasData = hasData,
            imported = false,
            missingFiles = missing,
            iconPath = previewDir?.let { cachePreviewIcon(properties, packageName, it) },
        )
        if (!candidate.isComplete) return candidate
        val existing = existingManifest(destination, candidate)
        return candidate.copy(imported = existing != null && existing.files.orEmpty().all { hasExpectedSize(destination, it) } &&
            appBackupRepository.isLocalRevisionIndexed(backupDir, "$packageName/user_0@$createdAt"))
    }

    private suspend fun existingManifest(destination: String, candidate: BackupCandidate): BackupManifest? {
        val path = PathUtil.getBackupManifestDst(destination)
        val json = rootService.readTextIfPresent(path) ?: return null
        val manifest = checkNotNull(rootService.gsonUtil.fromJson<BackupManifest?>(json, BackupManifest::class.java)) { "Invalid backup manifest: $path" }
        check(manifest.schemaVersion == BACKUP_MANIFEST_SCHEMA_VERSION && manifest.hasValidFileEntries()) { "Invalid backup manifest: $path" }
        check(candidate.matches(manifest)) { "A different backup already occupies $destination" }
        return manifest
    }

    private suspend fun hasExpectedSize(destination: String, file: BackupManifestFile): Boolean {
        val path = "$destination/${file.name}"
        return rootService.fileLengthIfPresent(path) == file.sizeBytes
    }

    private suspend fun readProperties(path: String): Properties {
        val file = File.createTempFile("titanium-", ".properties", context.cacheDir)
        return try {
            check(rootService.copyTo(path, file.path, overwrite = true)) { "Unable to read metadata" }
            Properties().apply { FileInputStream(file).use(::load) }
        } finally {
            file.delete()
        }
    }

    private fun Properties.icon(): ByteArray? = sequenceOf("app_gui_icon", "app_icon").mapNotNull { key ->
        val encoded = getProperty(key) ?: return@mapNotNull null
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return@mapNotNull null
        }
        appIconRepository.normalize(bytes)
    }.firstOrNull()

    private fun cachePreviewIcon(properties: Properties, packageName: String, dir: File): String? {
        val normalized = properties.icon() ?: return null
        val key = properties.getProperty("app_version_code")?.toLongOrNull() ?: 0
        return runCatching {
            dir.mkdirs()
            File(dir, "${packageName}_$key.png").apply { writeBytes(normalized) }.path
        }.getOrNull()
    }

    private fun Properties.apkChecksum(): String? =
        getProperty("app_apk_md5")?.trim()?.takeIf(String::isNotEmpty)?.also {
            require(apkChecksumPattern.matches(it)) { "Invalid APK checksum in Titanium Backup metadata" }
        }

    private suspend fun importBackup(candidate: BackupCandidate): BackupResult {
        val metadataPath = candidate.metadataPath
        val sourceDir = PathUtil.getParentPath(metadataPath)
        val fileName = PathUtil.getFileName(metadataPath)
        val packageName = candidate.packageName
        val createdAt = candidate.createdAt
        check(
            candidate.isComplete && PathUtil.isValidPackageName(packageName) && createdAt >= 0 &&
                metadataPattern.matchEntire(fileName)?.groupValues?.get(1) == packageName
        ) { "Invalid backup candidate: $fileName" }
        val properties = readProperties(metadataPath)
        val apkMd5 = candidate.apkMd5

        val label = properties.getProperty("app_label")
            ?: properties.getProperty("app_gui_label")
            ?: packageName
        val versionName = properties.getProperty("app_version_name").orEmpty()
        val versionCode = properties.getProperty("app_version_code")?.toLongOrNull() ?: 0
        check(versionName == candidate.versionName && versionCode == candidate.versionCode && apkMd5.equals(properties.apkChecksum(), ignoreCase = true)) {
            "Backup metadata changed since scan: $fileName"
        }
        val note = buildImportNote(
            personalNote = properties.getProperty("personal_note").orEmpty(),
            isProtected = properties.getProperty("is_protected") == "1",
        )
        val backupRoot = candidate.backupDir
        val appsDir = pathUtil.getLocalBackupAppsDir(backupRoot)
        val destination = "$appsDir/$packageName/user_0@$createdAt"
        check(FileUtil.isDescendant(backupRoot, destination)) { "Invalid import destination" }
        val existing = existingManifest(destination, candidate)
        val verifiedFiles = existing?.files.orEmpty().filter { file ->
            coroutineContext.ensureActive()
            val path = "$destination/${file.name}"
            hasExpectedSize(destination, file) && checkNotNull(rootService.calculateSHA256(path)) { "Unable to read $path" } == file.sha256
        }
        if (existing != null && verifiedFiles.size == existing.files.orEmpty().size) {
            val indexed = appBackupRepository.indexImportedRevision(backupRoot, destination)
            if (!rootService.exists(PathUtil.getAppIconPath(appsDir, packageName)) && !importIcon(properties, packageName, appsDir)) {
                appIconRepository.repairFromBackup(destination, appsDir, packageName, existing.apkCompression)
            }
            return BackupResult(packageName, label, versionName, createdAt, if (indexed) Status.IMPORTED else Status.SKIPPED)
        }
        val dataSource = if (candidate.hasData) "$sourceDir/${fileName.removeSuffix(".properties")}.tar.gz" else null
        if (dataSource != null) check(rootService.exists(dataSource)) { "Missing data archive: $dataSource" }
        val packageDir = PathUtil.getParentPath(destination)
        val stage = "${context.cacheDir}/titanium-import/${packageName}_$createdAt"
        check(FileUtil.isDescendant(context.cacheDir.path, stage)) { "Invalid import workspace" }
        check(rootService.deleteRecursively(stage)) { "Unable to clear the import workspace" }
        var createdDestination = false
        var committed = false
        val createdFiles = mutableListOf<String>()
        return try {
            check(rootService.mkdirsWithin(context.cacheDir.path, stage)) { "Unable to create the import workspace" }
            check(rootService.mkdirs(packageDir)) { "Unable to create import destination" }
            createdDestination = rootService.createDirectory(destination)
            val output = if (createdDestination) destination else "$stage/revision"
            if (!createdDestination) {
                check(rootService.mkdirsWithin(backupRoot, destination)) { "Invalid import destination: $destination" }
                check(rootService.mkdirs(output)) { "Unable to stage the converted backup" }
            }
            val states = mutableMapOf<DataType, DataState>().withDefault { DataState.NotSelected }
            val compression = existing?.let { previous ->
                CompressionType.entries.singleOrNull { type -> previous.files.orEmpty().any { it.name.endsWith(".${type.suffix}") } }
                    ?: error("Unknown compression in $destination")
            } ?: context.readCompressionType().first()
            val compressionArgs = compression.getCompressPara(context.readCompressionLevel().first())
            suspend fun reserve(path: String) {
                if (createdDestination) {
                    check(rootService.createNewFile(path)) { "File already exists or cannot be created: $path" }
                    createdFiles += path
                }
            }
            val importedApk = apkMd5?.let { checksum ->
                val apkSource = "$sourceDir/$packageName-$checksum.apk.gz"
                check(rootService.exists(apkSource)) { "Missing APK archive: $apkSource" }
                states[DataType.PACKAGE_APK] = DataState.Selected
                importApk(apkSource, checksum, stage, output, compression, compressionArgs, ::reserve)
            }

            val importedData = if (dataSource != null) {
                importData(dataSource, packageName, stage, output, compression, compressionArgs, ::reserve)
            } else {
                ImportedData(emptySet(), 0)
            }
            importedData.types.forEach { states[it] = DataState.Selected }
            check(states.values.any { it == DataState.Selected }) { "No supported backup content was found" }

            val app = createPackage(
                packageName = packageName,
                label = label,
                versionName = versionName,
                versionCode = versionCode,
                createdAt = createdAt,
                compression = compression,
                systemApp = properties.getProperty("app_is_system") == "1",
                states = states,
                backupDir = backupRoot,
            )
            val retained = verifiedFiles.filterNot { it.name == ConfigsPackageRestoreName }
            check(retained.all { rootService.exists("$output/${it.name}") }) { "Source no longer contains all existing data types" }
            app.displayStats = appBackupRepository.calculateDisplayStats(output, compression, app.dataStates, retained)
            reserve(PathUtil.getPackageRestoreConfigDst(output))
            check(rootService.writeJson(app.toRestoreConfig(), PathUtil.getPackageRestoreConfigDst(output)).isSuccess) {
                "Unable to write app metadata"
            }
            reserve(PathUtil.getBackupManifestDst(output))
            val apkEntries = if (retained.any { it.name == "apk.${compression.suffix}" }) existing?.apkEntries else setOf("base.apk")
            val manifest = checkNotNull(appBackupRepository.writeManifest(app, createdAt, output, existing?.note ?: note, retained, candidate.source, apkEntries)) {
                "Unable to write the backup manifest"
            }
            if (createdDestination) {
                committed = true
                withContext(NonCancellable) { appBackupRepository.indexImportedRevision(backupRoot, destination, importedApk) }
            } else {
                publishRepair(candidate, output, destination, manifest, existing, retained, importedApk)
            }
            if (!importIcon(properties, packageName, appsDir) && importedApk != null) {
                appIconRepository.saveApkIcon(importedApk, appsDir, packageName)
            }
            BackupResult(
                packageName = packageName,
                label = label,
                versionName = versionName,
                createdAt = createdAt,
                status = if (importedData.skippedEntries == 0) Status.IMPORTED else Status.PARTIAL,
                skippedEntries = importedData.skippedEntries,
            )
        } finally {
            withContext(NonCancellable) {
                if (createdDestination && !committed) {
                    createdFiles.forEach { rootService.removeEntry(it) }
                    rootService.removeEntry(destination, directory = true)
                }
                rootService.deleteRecursively(stage)
                rootService.removeEntry(PathUtil.getParentPath(stage), directory = true)
                rootService.removeEntry(packageDir, directory = true)
            }
        }
    }

    private suspend fun publishRepair(
        candidate: BackupCandidate, source: String, destination: String, manifest: BackupManifest,
        existing: BackupManifest?, retained: List<BackupManifestFile>, apk: String?,
    ) {
        val originalNames = existing?.files.orEmpty().mapTo(mutableSetOf()) { it.name }
        if (existing != null) {
            check(manifest.contentMask == existing.contentMask && manifest.files.orEmpty().map { it.name }.toSet() == originalNames) {
                "Converted files do not match the existing backup: $destination"
            }
            originalNames += BackupManifestName
        }
        val manifestFile = checkNotNull(appBackupRepository.getManifestFile(PathUtil.getBackupManifestDst(source)))
        val staged = mutableMapOf<String, BackupManifestFile>()
        val reserved = mutableSetOf<String>()
        try {
            for (file in (manifest.files.orEmpty() - retained.toSet()) + manifestFile) {
                val target = "$destination/${file.name}"
                if (file.name !in originalNames) {
                    if (rootService.exists(target) && rootService.calculateSHA256(target) == file.sha256) continue
                    check(rootService.createNewFile(target)) { "Refusing to overwrite $target" }
                    reserved += target
                }
                val temporary = "$destination/.import-${UUID.randomUUID()}"
                staged[temporary] = file
                check(rootService.copyTo("$source/${file.name}", temporary, overwrite = false)) { "Unable to copy ${file.name}" }
                check(rootService.calculateSHA256(temporary) == file.sha256) { "Unable to verify ${file.name}" }
            }
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                appBackupRepository.indexImportedRevision(candidate.backupDir, destination, apk) {
                    if (existing != null) check(existingManifest(destination, candidate) == existing) { "Backup changed during import: $destination" }
                    staged.forEach { (temporary, file) ->
                        val target = "$destination/${file.name}"
                        check(rootService.renameTo(temporary, target)) { "Unable to publish ${file.name}" }
                        reserved.remove(target)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                (staged.keys + reserved).forEach { rootService.removeEntry(it) }
            }
        }
    }

    private suspend fun importApk(
        source: String,
        expectedMd5: String,
        stage: String,
        destination: String,
        compression: CompressionType,
        compressionArgs: String,
        reserve: suspend (String) -> Unit,
    ): String {
        val apk = "$stage/base.apk"
        check(shell("busybox gzip -dc ${shellQuote(source)} > ${shellQuote(apk)}")) { "Invalid APK archive" }
        check(shell("busybox unzip -t ${shellQuote(apk)} >/dev/null")) { "Invalid APK" }
        val actualMd5 = BaseUtil.execute("md5sum ${shellQuote(apk)}").outString.substringBefore(' ').trim()
        check(actualMd5.equals(expectedMd5, ignoreCase = true)) { "APK checksum mismatch" }
        val target = "$destination/${DataType.PACKAGE_APK.type}.${compression.suffix}"
        reserve(target)
        check(Tar.compressApks(context.cacheDir, mapOf("base.apk" to apk), target, compressionArgs).isSuccess) { "Unable to convert the APK" }
        return apk
    }

    private suspend fun importData(
        source: String,
        packageName: String,
        stage: String,
        destination: String,
        compression: CompressionType,
        compressionArgs: String,
        reserve: suspend (String) -> Unit,
    ): ImportedData {
        val extracted = "$stage/data"
        check(rootService.mkdirs(extracted))
        val linkDir = "$stage/links"
        val extraction = rootService.extractArchive(
            source = source,
            destination = extracted,
            compression = "gzip",
            workspace = linkDir,
            preservePermissions = false,
        )
        check(extraction.result.isSuccess) { extraction.result.outString.ifBlank { "Invalid or unsafe data archive" } }
        val sources = listOf(
            DataType.PACKAGE_USER to listOf("data/data/$packageName", "data/user/0/$packageName"),
            DataType.PACKAGE_USER_DE to listOf("data/user_de/0/$packageName"),
            DataType.PACKAGE_DATA to listOf("data/media/0/Android/data/$packageName", "storage/emulated/0/Android/data/$packageName", "sdcard/Android/data/$packageName"),
            DataType.PACKAGE_OBB to listOf("data/media/0/Android/obb/$packageName", "storage/emulated/0/Android/obb/$packageName", "sdcard/Android/obb/$packageName"),
            DataType.PACKAGE_MEDIA to listOf("data/media/0/Android/media/$packageName", "storage/emulated/0/Android/media/$packageName", "sdcard/Android/media/$packageName"),
        )
        val restoredLinks = if (extraction.pendingLinks > 0) {
            rootService.restoreArchiveLinks(linkDir, extracted, sources.flatMap { it.second })
        } else 0
        val imported = mutableSetOf<DataType>()
        sources.forEach { (type, candidates) ->
            val sourcePath = candidates.asSequence().map { "$extracted/$it" }.firstOrNull { rootService.existsChecked(it) } ?: return@forEach
            val parent = sourcePath.substringBeforeLast('/')
            val target = "$destination/${type.type}.${compression.suffix}"
            reserve(target)
            val result = Tar.compress(type.archiveExclusions(packageName).tarPatterns, "", parent, packageName, target, compressionArgs)
            check(result.isSuccess) { result.outString.ifBlank { "Unable to convert ${type.type}" } }
            if (Tar.hasContent(target, packageName, compression.decompressPara).getOrThrow()) {
                imported += type
            } else {
                rootService.removeEntry(target)
            }
        }
        return ImportedData(imported, extraction.skippedEntries + extraction.pendingLinks - restoredLinks)
    }

    private suspend fun importIcon(properties: Properties, packageName: String, appsDir: String): Boolean {
        val icon = properties.icon() ?: return false
        return rootService.writeBytes(icon, PathUtil.getAppIconPath(appsDir, packageName))
    }

    private fun buildImportNote(personalNote: String, isProtected: Boolean): String = buildList {
        val source = if (isProtected) "$IMPORT_NOTE (protected)" else IMPORT_NOTE
        add(source)
        personalNote.trim().takeIf { it.isNotEmpty() && it != IMPORT_NOTE && it != source }?.let(::add)
    }.joinToString("\n")

    private fun createPackage(
        packageName: String,
        label: String,
        versionName: String,
        versionCode: Long,
        createdAt: Long,
        compression: CompressionType,
        systemApp: Boolean,
        states: Map<DataType, DataState>,
        backupDir: String,
    ) = PackageEntity(
        id = 0,
        indexInfo = PackageIndexInfo(OpType.RESTORE, packageName, 0, compression, createdAt, "", backupDir),
        packageInfo = PackageInfo(
            label = label,
            versionName = versionName,
            versionCode = versionCode,
            flags = if (systemApp) ApplicationInfo.FLAG_SYSTEM else 0,
            firstInstallTime = 0,
            lastUpdateTime = 0,
        ),
        extraInfo = PackageExtraInfo(0, false, emptyList(), "", createdAt, false, false, firstUpdated = false, enabled = true),
        dataStates = PackageDataStates(
            apkState = states.getValue(DataType.PACKAGE_APK),
            userState = states.getValue(DataType.PACKAGE_USER),
            userDeState = states.getValue(DataType.PACKAGE_USER_DE),
            dataState = states.getValue(DataType.PACKAGE_DATA),
            obbState = states.getValue(DataType.PACKAGE_OBB),
            mediaState = states.getValue(DataType.PACKAGE_MEDIA),
            permissionState = DataState.NotSelected,
            ssaidState = DataState.NotSelected,
        ),
        storageStats = PackageStorageStats(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(),
    )

    private data class LabelGroup(val name: String, val color: Long, val packages: MutableSet<String> = sortedSetOf())

    private fun readLabels(file: File): List<LabelGroup> = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { db ->
        db.rawQuery(
            "SELECT l.labelName, l.labelColor, p.packageName FROM labels l JOIN packages p ON p.id_label = l._id ORDER BY l._id, p.packageName",
            null,
        ).use { cursor ->
            val groups = mutableMapOf<String, LabelGroup>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val packageName = cursor.getString(2)
                require(!name.isNullOrBlank() && packageName != null) { "Invalid Titanium Backup label entry" }
                groups.getOrPut(name) { LabelGroup(name, 0xFF000000L or (cursor.getLong(1) and 0xFFFFFFL)) }.packages += packageName
            }
            groups.values.toList()
        }
    }

    private suspend fun shell(command: String): Boolean = BaseUtil.execute(command).isSuccess

}
