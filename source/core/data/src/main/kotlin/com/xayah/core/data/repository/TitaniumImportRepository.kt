package com.xayah.core.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.xayah.core.model.BackupManifest
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
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
import com.xayah.core.util.FileUtil
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Singleton
class TitaniumImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val pathUtil: PathUtil,
    private val appBackupRepository: AppBackupRepository,
    private val appIconRepository: AppIconRepository,
    private val xposedModuleDetector: XposedModuleDetector,
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
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val createdAt: Long,
        val hasApk: Boolean,
        val hasData: Boolean,
        val imported: Boolean,
        val missingFiles: List<String>,
        val iconPath: String?,
        val scanError: String? = null,
    ) {
        val id: String get() = metadataPath
        val isComplete: Boolean get() = scanError == null && missingFiles.isEmpty() && (hasApk || hasData)
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

    companion object {
        const val DEFAULT_BACKUP_DIR = "/storage/emulated/0/TitaniumBackup"
        const val DEFAULT_LABEL_DB = "/storage/emulated/0/data/com.keramidas.TitaniumBackup/settings/databases~custom"
        const val IMPORT_NOTE = "from Titanium Backup"
        private val metadataPattern = Regex("^(.+)-(\\d{8})-(\\d{6})\\.properties$")
    }

    suspend fun detectBackupDir(): String? = DEFAULT_BACKUP_DIR.takeIf { rootService.exists(it) }

    suspend fun detectLabelDatabase(): String? = DEFAULT_LABEL_DB.takeIf { rootService.exists(it) }

    suspend fun scanBackups(
        sourceDir: String,
        onProgress: suspend (completed: Int, total: Int) -> Unit,
    ): List<BackupCandidate> {
        clearPreviewCache()
        val metadataFiles = rootService.listFilePaths(sourceDir, listFiles = true, listDirs = false)
            .filter { metadataPattern.matches(PathUtil.getFileName(it)) }
            .sorted()
        onProgress(0, metadataFiles.size)
        return metadataFiles.mapIndexed { index, path ->
            coroutineContext.ensureActive()
            try {
                scanBackup(sourceDir, path)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val name = PathUtil.getFileName(path)
                BackupCandidate(
                    metadataPath = path,
                    packageName = metadataPattern.matchEntire(name)?.groupValues?.get(1).orEmpty(),
                    label = name,
                    versionName = "",
                    versionCode = 0,
                    createdAt = 0,
                    hasApk = false,
                    hasData = false,
                    imported = false,
                    missingFiles = emptyList(),
                    iconPath = null,
                    scanError = error.message.orEmpty(),
                )
            }.also { onProgress(index + 1, metadataFiles.size) }
        }
    }

    suspend fun scanLabels(databasePath: String): List<LabelCandidate> {
        val localDb = File(context.cacheDir, "titanium-labels.db")
        localDb.delete()
        check(rootService.copyTo(databasePath, localDb.path, overwrite = true)) { "Unable to read the Titanium Backup label database" }
        return try {
            val labels = labelsRepo.getLabels().mapTo(mutableSetOf(), LabelEntity::label)
            val colors = labelsRepo.getLabelColors()
            val refs = labelsRepo.getAppRefs()
                .filter { it.userId == 0 && it.preserveId == 0L }
                .groupBy { it.label }
                .mapValues { (_, values) -> values.mapTo(mutableSetOf()) { it.packageName } }
            readLabels(localDb).map { group ->
                LabelCandidate(
                    name = group.name,
                    color = group.color,
                    packages = group.packages,
                    imported = group.name in labels && colors[group.name] == group.color &&
                        refs[group.name].orEmpty().containsAll(group.packages),
                )
            }
        } finally {
            localDb.delete()
        }
    }

    fun clearPreviewCache() {
        FileUtil.deleteRecursively(File(context.cacheDir, "titanium-preview").path)
    }

    suspend fun importBackups(
        candidates: List<BackupCandidate>,
        onProgress: suspend (completed: Int, total: Int, result: BackupResult?) -> Unit,
    ): List<BackupResult> {
        val results = mutableListOf<BackupResult>()
        val packageNames = candidates.map(BackupCandidate::packageName)
            .filter(PathUtil::isValidPackageName)
            .toSet()
        onProgress(0, candidates.size, null)
        try {
            candidates.forEachIndexed { index, candidate ->
                coroutineContext.ensureActive()
                val result = try {
                    importBackup(PathUtil.getParentPath(candidate.metadataPath), candidate.metadataPath)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    BackupResult(
                        packageName = candidate.packageName,
                        label = candidate.label,
                        versionName = candidate.versionName,
                        createdAt = candidate.createdAt,
                        status = Status.FAILED,
                        detail = error.message.orEmpty(),
                    )
                }
                results += result
                onProgress(index + 1, candidates.size, result)
            }
        } finally {
            withContext(NonCancellable) {
                deleteEmptyImportWorkspace()
                cleanupBackupDirectories(packageNames)
                appBackupRepository.rebuildLocalIndex { _, _, _ -> }
                clearPreviewCache()
            }
        }
        return results
    }

    suspend fun importLabels(
        candidates: List<LabelCandidate>,
        onProgress: suspend (completed: Int, total: Int, result: LabelResult?) -> Unit,
    ): List<LabelResult> {
        val results = mutableListOf<LabelResult>()
        onProgress(0, candidates.size, null)
        candidates.forEachIndexed { index, group ->
                coroutineContext.ensureActive()
                val result = try {
                    withContext(NonCancellable) {
                        labelsRepo.addLabels(listOf(LabelEntity(group.name)))
                        labelsRepo.setLabelColor(group.name, group.color)
                        labelsRepo.addLabelAppCrossRefs(
                            group.packages.map { packageName ->
                                LabelAppCrossRefEntity(group.name, packageName, 0, 0)
                            }
                        )
                        LabelResult(group.name, group.color, group.packages.size, Status.IMPORTED)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LabelResult(group.name, group.color, group.packages.size, Status.FAILED, error.message.orEmpty())
                }
                results += result
                onProgress(index + 1, candidates.size, result)
            }
        return results
    }

    private suspend fun scanBackup(sourceDir: String, metadataPath: String): BackupCandidate {
        val fileName = PathUtil.getFileName(metadataPath)
        val match = checkNotNull(metadataPattern.matchEntire(fileName))
        val packageName = match.groupValues[1]
        check(PathUtil.isValidPackageName(packageName)) { "Invalid package name" }
        val createdAt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .parse("${match.groupValues[2]}-${match.groupValues[3]}")?.time
            ?: error("Invalid backup timestamp")
        val properties = readProperties(metadataPath)
        val label = properties.getProperty("app_label")
            ?: properties.getProperty("app_gui_label")
            ?: packageName
        val apkMd5 = properties.getProperty("app_apk_md5")
        val apkName = apkMd5?.let { "$packageName-$it.apk.gz" }
        val apkPath = apkName?.let { "$sourceDir/$it" }
        val dataName = fileName.removeSuffix(".properties") + ".tar.gz"
        val dataPath = "$sourceDir/$dataName"
        val hasApk = apkPath != null && rootService.exists(apkPath) && rootService.calculateSize(apkPath) > 0
        val hasData = rootService.exists(dataPath) && rootService.calculateSize(dataPath) > 0
        val missing = buildList {
            if (apkName != null && !hasApk) add(apkName)
            if (!hasApk && !hasData) add(dataName)
        }
        val destination = "${pathUtil.getLocalBackupAppsDir()}/$packageName/user_0@$createdAt"
        return BackupCandidate(
            metadataPath = metadataPath,
            packageName = packageName,
            label = label,
            versionName = properties.getProperty("app_version_name").orEmpty(),
            versionCode = properties.getProperty("app_version_code")?.toLongOrNull() ?: 0,
            createdAt = createdAt,
            hasApk = hasApk,
            hasData = hasData,
            imported = isImported(destination),
            missingFiles = missing,
            iconPath = cachePreviewIcon(properties, packageName),
        )
    }

    private suspend fun isImported(destination: String): Boolean {
        val manifest = rootService.readJson<BackupManifest>(PathUtil.getBackupManifestDst(destination)) ?: return false
        return manifest.files.orEmpty().isNotEmpty() && manifest.files.orEmpty().all { file ->
            val path = "$destination/${file.name}"
            rootService.exists(path) && rootService.calculateSize(path) == file.sizeBytes
        }
    }

    private suspend fun readProperties(path: String): Properties {
        val file = File(context.cacheDir, "titanium.properties")
        check(rootService.copyTo(path, file.path, overwrite = true)) { "Unable to read metadata" }
        return try {
            Properties().apply { FileInputStream(file).use(::load) }
        } finally {
            file.delete()
        }
    }

    private fun cachePreviewIcon(properties: Properties, packageName: String): String? {
        val encoded = properties.getProperty("app_gui_icon") ?: properties.getProperty("app_icon") ?: return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        val normalized = appIconRepository.normalize(bytes) ?: return null
        val dir = File(context.cacheDir, "titanium-preview").apply { mkdirs() }
        val key = properties.getProperty("app_version_code").orEmpty()
        return runCatching { File(dir, "${packageName}_$key.png").apply { writeBytes(normalized) }.path }.getOrNull()
    }

    private suspend fun importBackup(sourceDir: String, metadataPath: String): BackupResult {
        val fileName = PathUtil.getFileName(metadataPath)
        val match = checkNotNull(metadataPattern.matchEntire(fileName))
        val packageName = match.groupValues[1]
        check(PathUtil.isValidPackageName(packageName)) { "Invalid package name" }
        val createdAt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .parse("${match.groupValues[2]}-${match.groupValues[3]}")?.time
            ?: error("Invalid backup timestamp")
        val tempMetadata = File(context.cacheDir, "titanium.properties")
        check(rootService.copyTo(metadataPath, tempMetadata.path, overwrite = true)) { "Unable to read metadata" }
        val properties = Properties().apply { FileInputStream(tempMetadata).use(::load) }
        tempMetadata.delete()

        val label = properties.getProperty("app_label")
            ?: properties.getProperty("app_gui_label")
            ?: packageName
        val versionName = properties.getProperty("app_version_name").orEmpty()
        val versionCode = properties.getProperty("app_version_code")?.toLongOrNull() ?: 0
        val note = buildImportNote(
            personalNote = properties.getProperty("personal_note").orEmpty(),
            isProtected = properties.getProperty("is_protected") == "1",
        )
        val backupRoot = context.localBackupSaveDir()
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val destination = "$appsDir/$packageName/user_0@$createdAt"
        check(FileUtil.isDescendant(backupRoot, destination)) { "Invalid import destination" }
        check(rootService.mkdirs(destination)) { "Unable to create import destination" }
        if (rootService.exists(PathUtil.getBackupManifestDst(destination))) {
            updateImportedNote(destination, note)
            if (!importIcon(properties, packageName)) {
                appIconRepository.repairFromBackup(
                    destination,
                    pathUtil.getLocalBackupAppsDir(),
                    packageName,
                    overwrite = true,
                )
            }
            return BackupResult(packageName, label, versionName, createdAt, Status.SKIPPED)
        }
        check(rootService.listFilePathsChecked(destination).getOrThrow().isEmpty()) { "Import destination is not empty" }

        val stage = "${context.cacheDir}/titanium-import/${packageName}_$createdAt"
        check(FileUtil.isDescendant(context.cacheDir.path, stage)) { "Invalid import workspace" }
        check(rootService.deleteRecursively(stage)) { "Unable to clear the import workspace" }
        check(rootService.mkdirsWithin(context.cacheDir.path, stage)) { "Unable to create the import workspace" }
        return try {
            val states = mutableMapOf<DataType, DataState>().withDefault { DataState.NotSelected }
            val compression = context.readCompressionType().first()
            val compressionArgs = compression.getCompressPara(context.readCompressionLevel().first())
            val apkMd5 = properties.getProperty("app_apk_md5")
            val apkSource = apkMd5?.let { "$sourceDir/$packageName-$it.apk.gz" }
            var importedApk: String? = null
            if (apkSource != null && rootService.exists(apkSource)) {
                importedApk = importApk(apkSource, apkMd5, stage, destination, compression, compressionArgs)
                states[DataType.PACKAGE_APK] = DataState.Selected
            }

            val dataSource = "$sourceDir/${fileName.removeSuffix(".properties")}.tar.gz"
            val importedData = if (rootService.exists(dataSource)) {
                importData(dataSource, packageName, stage, destination, compression, compressionArgs)
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
                xposedModule = importedApk?.let { xposedModuleDetector.isModule(it) } == true,
                states = states,
            )
            app.displayStats = appBackupRepository.calculateDisplayStats(destination, compression, app.dataStates)
            check(rootService.writeJson(app.toRestoreConfig(), PathUtil.getPackageRestoreConfigDst(destination)).isSuccess) {
                "Unable to write app metadata"
            }
            checkNotNull(appBackupRepository.writeManifest(app, createdAt, destination, note)) { "Unable to write the backup manifest" }
            if (!importIcon(properties, packageName) && importedApk != null) {
                appIconRepository.saveApkIcon(importedApk, pathUtil.getLocalBackupAppsDir(), packageName)
            }
            BackupResult(
                packageName = packageName,
                label = label,
                versionName = versionName,
                createdAt = createdAt,
                status = if (importedData.skippedEntries == 0) Status.IMPORTED else Status.PARTIAL,
                skippedEntries = importedData.skippedEntries,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                rootService.deleteRecursively(destination)
            }
            throw error
        } finally {
            withContext(NonCancellable) {
                rootService.deleteRecursively(stage)
                deleteEmptyImportWorkspace()
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
    ): String {
        val apkDir = "$stage/apk"
        check(rootService.mkdirs(apkDir))
        val apk = "$apkDir/base.apk"
        check(shell("busybox gzip -dc ${quote(source)} > ${quote(apk)}")) { "Invalid APK archive" }
        check(shell("busybox unzip -t ${quote(apk)} >/dev/null")) { "Invalid APK" }
        val actualMd5 = BaseUtil.execute("md5sum ${quote(apk)}").outString.substringBefore(' ').trim()
        check(actualMd5.equals(expectedMd5, ignoreCase = true)) { "APK checksum mismatch" }
        val target = "$destination/${DataType.PACKAGE_APK.type}.${compression.suffix}"
        check(Tar.compressInCur(apkDir, "./*.apk", target, compressionArgs).isSuccess) { "Unable to convert the APK" }
        return apk
    }

    private suspend fun importData(
        source: String,
        packageName: String,
        stage: String,
        destination: String,
        compression: CompressionType,
        compressionArgs: String,
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
        val restoredLinks = rootService.restoreArchiveLinks(linkDir, extracted)
        val sources = listOf(
            DataType.PACKAGE_USER to listOf("$extracted/data/data/$packageName", "$extracted/data/user/0/$packageName"),
            DataType.PACKAGE_USER_DE to listOf("$extracted/data/user_de/0/$packageName"),
            DataType.PACKAGE_DATA to listOf("$extracted/data/media/0/Android/data/$packageName", "$extracted/storage/emulated/0/Android/data/$packageName", "$extracted/sdcard/Android/data/$packageName"),
            DataType.PACKAGE_OBB to listOf("$extracted/data/media/0/Android/obb/$packageName", "$extracted/storage/emulated/0/Android/obb/$packageName", "$extracted/sdcard/Android/obb/$packageName"),
            DataType.PACKAGE_MEDIA to listOf("$extracted/data/media/0/Android/media/$packageName", "$extracted/storage/emulated/0/Android/media/$packageName", "$extracted/sdcard/Android/media/$packageName"),
        )
        val imported = mutableSetOf<DataType>()
        sources.forEach { (type, candidates) ->
            val sourcePath = candidates.firstOrNull { rootService.exists(it) } ?: return@forEach
            val parent = sourcePath.substringBeforeLast('/')
            val target = "$destination/${type.type}.${compression.suffix}"
            val exclusions = when (type) {
                DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE ->
                    listOf(".ota", "cache", "lib", "code_cache", "no_backup").map { "$packageName/$it" }
                DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA ->
                    listOf("$packageName/cache", "Backup_*")
                else -> emptyList()
            }
            val result = Tar.compress(exclusions, "", parent, packageName, target, compressionArgs)
            check(result.isSuccess) { result.outString.ifBlank { "Unable to convert ${type.type}" } }
            if (Tar.hasContent(target, packageName, compression.decompressPara).isSuccess) {
                imported += type
            } else {
                rootService.deleteRecursively(target)
            }
        }
        return ImportedData(imported, extraction.skippedEntries + extraction.pendingLinks - restoredLinks)
    }

    private suspend fun importIcon(properties: Properties, packageName: String): Boolean {
        val encoded = properties.getProperty("app_gui_icon") ?: properties.getProperty("app_icon") ?: return false
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return false
        return appIconRepository.saveEncodedIcon(bytes, pathUtil.getLocalBackupAppsDir(), packageName)
    }

    private suspend fun updateImportedNote(destination: String, note: String) {
        val normalized = note.trim()
        if (normalized.isEmpty()) return
        val manifestPath = PathUtil.getBackupManifestDst(destination)
        val manifest = rootService.readJson<com.xayah.core.model.BackupManifest>(manifestPath) ?: return
        if (manifest.note.orEmpty() != normalized) {
            check(rootService.writeJson(manifest.copy(note = normalized), manifestPath).isSuccess) {
                "Unable to import note"
            }
        }
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
        xposedModule: Boolean,
        states: Map<DataType, DataState>,
    ) = PackageEntity(
        id = 0,
        indexInfo = PackageIndexInfo(OpType.RESTORE, packageName, 0, compression, createdAt, "", context.localBackupSaveDir()),
        packageInfo = PackageInfo(
            label = label,
            versionName = versionName,
            versionCode = versionCode,
            flags = if (systemApp) ApplicationInfo.FLAG_SYSTEM else 0,
            firstInstallTime = 0,
            lastUpdateTime = 0,
            isXposedModule = xposedModule,
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

    private data class LabelGroup(val name: String, val color: Long, val packages: List<String>)

    private fun readLabels(file: File): List<LabelGroup> = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { db ->
        db.rawQuery(
            "SELECT l.labelName, l.labelColor, p.packageName FROM labels l JOIN packages p ON p.id_label = l._id ORDER BY l._id, p.packageName",
            null,
        ).use { cursor ->
            val rows = mutableMapOf<Pair<String, Long>, MutableList<String>>()
            while (cursor.moveToNext()) {
                val color = 0xFF000000L or (cursor.getLong(1) and 0xFFFFFFL)
                rows.getOrPut(cursor.getString(0) to color, ::mutableListOf) += cursor.getString(2)
            }
            rows.map { (key, packages) -> LabelGroup(key.first, key.second, packages.distinct()) }
        }
    }

    private suspend fun shell(command: String): Boolean = BaseUtil.execute(command).isSuccess

    private suspend fun deleteEmptyImportWorkspace() {
        val path = "${context.cacheDir}/titanium-import"
        val children = rootService.listFilePathsChecked(path).getOrNull() ?: return
        if (rootService.exists(path) && children.isEmpty()) {
            rootService.deleteRecursively(path)
        }
    }

    private suspend fun cleanupBackupDirectories(packageNames: Set<String>) {
        val appsDir = pathUtil.getLocalBackupAppsDir()
        packageNames.forEach { packageName ->
            val packageDir = "$appsDir/$packageName"
            if (FileUtil.isDescendant(appsDir, packageDir)) {
                rootService.clearEmptyDirectoriesRecursively(packageDir)
            }
        }
    }

    private fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
}
