package com.xayah.core.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.model.BackupManifest
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.model.CompressionType
import com.xayah.core.model.ConfigsPackageRestoreName
import com.xayah.core.model.DataState
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.toPackageEntity
import com.xayah.core.model.toBackupApp
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.parcelables.ArchiveExtractionParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationRefreshTest {
    @Test
    fun verificationPreservesConcurrentEditsAndDeletedRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.cacheDir, "verification-refresh-test").apply { mkdirs() }
        val previousPath = context.localBackupSaveDir()
        try {
            context.saveBackupSavePath(directory.path)
            val config = PackageRestoreConfig(
                packageName = "com.example.module", userId = 0, createdAt = 1234, compressionType = CompressionType.TAR,
                label = "Module", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(
                    userState = DataState.NotSelected, userDeState = DataState.NotSelected, dataState = DataState.NotSelected,
                    obbState = DataState.NotSelected, mediaState = DataState.NotSelected,
                ),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            )
            val restore = config.toPackageEntity("", directory.path).copy(id = 2)
            val installed = restore.copy(id = 1, indexInfo = restore.indexInfo.copy(opType = OpType.BACKUP, preserveId = 0))
            val revisionDir = File(directory, "apps/${restore.archivesRelativeDir}").apply { mkdirs() }
            File(revisionDir, "unrelated.txt").writeText("not backup content")
            val icon = File(revisionDir.parentFile, "icon.bin").apply { createNewFile() }
            val binder = Binder()
            var storageAvailable = true
            var canList = true
            var unreadableFile: String? = null
            val inspectedApks = mutableListOf<String>()
            val remote = Proxy.newProxyInstance(
                IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "extractArchive" -> {
                        inspectedApks += args!![0] as String
                        ArchiveExtractionParcelable(-1, emptyList(), 0, 0)
                    }
                    "exists" -> storageAvailable && File(args!![0] as String).exists()
                    "deleteRecursively" -> FileUtil.deleteRecursively(args!![0] as String)
                    "removeEntry" -> FileUtil.removeEntry(args!![0] as String, args[1] as Boolean)
                    "mkdirs" -> File(args!![0] as String).let { it.mkdirs() || it.isDirectory }
                    "replaceFile" -> {
                        File(args!![0] as String).copyTo(File(args[1] as String), overwrite = true)
                        true
                    }
                    "fileLength" -> File(args!![0] as String).let {
                        if (!it.exists()) -1L else {
                            check(it.isFile && it.name != unreadableFile)
                            it.length()
                        }
                    }
                    "calculateSHA256" -> MessageDigest.getInstance("SHA-256")
                        .digest(File(args!![0] as String).readBytes()).joinToString("") { "%02x".format(it) }
                    "readDirectory", "readText" -> {
                        val parcel = Parcel.obtain()
                        val file = File.createTempFile("parcel-", ".tmp", directory)
                        try {
                            val path = File(args!![0] as String)
                            if (method.name == "readDirectory") {
                                if (!canList) return@newProxyInstance null
                                parcel.writeStringList(path.listFiles()!!.filter(File::isDirectory).map(File::getPath))
                                parcel.writeStringList(path.listFiles()!!.filter(File::isFile).map(File::getPath))
                            } else {
                                check(path.name != unreadableFile) { "Read failed: $path" }
                                parcel.writeString(FileUtil.readText(path.path))
                            }
                            file.writeBytes(parcel.marshall())
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        } finally {
                            file.delete()
                            parcel.recycle()
                        }
                    }
                    "queryInstalled" -> true
                    "getPackageInfoAsUser" -> PackageInfo().apply {
                        applicationInfo = ApplicationInfo().apply {
                            metaData = Bundle().apply { putInt("xposedminversion", 93) }
                        }
                    }
                    else -> error("Unexpected root operation: ${method.name}")
                }
            }
            val root = RemoteRootService(context).apply {
                javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
            }
            val dao = database.packageDao()
            var removeAfterRead = false
            val edited = mutableMapOf<Long, PackageEntity?>()
            suspend fun editAfterRead(app: PackageEntity) {
                val current = if (removeAfterRead) null else app.copy(
                    extraInfo = app.extraInfo.copy(blocked = true, activated = true),
                    dataStates = app.dataStates.copy(apkState = DataState.NotSelected),
                )
                if (current == null) dao.delete(app.id) else dao.upsert(current)
                edited[app.id] = current
            }
            val interleaved = object : PackageDao by dao {
                override suspend fun query(packageName: String, opType: OpType, userId: Int): PackageEntity? =
                    dao.query(packageName, opType, userId)?.also { editAfterRead(it) }

                override suspend fun queryRevisions(packageName: String, opType: OpType, userId: Int, preserveId: Long): List<PackageEntity> =
                    dao.queryRevisions(packageName, opType, userId, preserveId).also { apps -> apps.forEach { editAfterRead(it) } }
            }
            val paths = PathUtil(context)
            val repository = AppBackupRepository(
                context, database,
                PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), interleaved, paths),
                AppIconRepository(context, root, paths), XposedModuleDetector(context, root), root,
            )
            val revision = BackupRevisionEntity(config.packageName, 0, 1234, "1.0", 1, BackupEngine.LEGACY, ":${directory.path}", restore.archivesRelativeDir, 1)
            root.writeJson(config, PathUtil.getPackageRestoreConfigDst(revisionDir.path))
            assertNull(repository.writeManifest(restore, 1234, revisionDir.path))
            File(revisionDir, "apk.tar").writeBytes(byteArrayOf(1, 2, 3))
            for (remove in listOf(false, true)) {
                removeAfterRead = remove
                dao.upsert(listOf(installed, restore))
                root.writeJson(config, PathUtil.getPackageRestoreConfigDst(revisionDir.path))
                val manifest = repository.writeManifest(restore, 1234, revisionDir.path)
                assertEquals(listOf("apk.tar", ConfigsPackageRestoreName), manifest?.files?.map { it.name })

                val configFile = File(PathUtil.getPackageRestoreConfigDst(revisionDir.path))
                val manifestFile = File(PathUtil.getBackupManifestDst(revisionDir.path))
                val metadata = listOf(configFile, manifestFile).associateWith(File::readBytes)
                assertEquals(BackupVerificationStatus.VALID, repository.inspectRevision(revision).status)
                metadata.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
                assertEquals(
                    listOf(
                        edited[installed.id]?.let { it.copy(packageInfo = it.packageInfo.copy(isXposedModule = true)) },
                        edited[restore.id]?.let { it.copy(packageInfo = it.packageInfo.copy(isXposedModule = true), displayStats = PackageDataStats(apkBytes = 3)) },
                    ),
                    listOf(dao.queryById(installed.id), dao.queryById(restore.id)),
                )
            }
            val configPath = PathUtil.getPackageRestoreConfigDst(revisionDir.path)
            val manifestPath = PathUtil.getBackupManifestDst(revisionDir.path)
            val original = checkNotNull(root.readJson<BackupManifest>(manifestPath))
            root.writeJson(config.copy(compressionType = CompressionType.ZSTD), configPath)
            val configEntry = checkNotNull(repository.getManifestFile(configPath))
            root.writeJson(original.copy(files = original.files.orEmpty().map {
                if (it.name == ConfigsPackageRestoreName) configEntry else it
            }), manifestPath)
            val mismatched = repository.inspectRevision(revision)
            assertEquals(AppBackupRepository.VerificationIssueType.METADATA_MISMATCH, mismatched.issues.single().type)
            assertEquals(false, mismatched.canClean)
            assertEquals(emptyList<AppBackupRepository.RebuildResult>(), repository.rebuildLocalIndex { _, _, _ -> })
            root.writeJson(config, configPath)
            root.writeJson(original, manifestPath)
            dao.upsert(restore)
            database.appBackupDao().upsertApps(listOf(AppKey(revision.packageName, revision.userId).toBackupApp()))
            database.appBackupDao().insertRevision(revision)
            val baseOnly = setOf("base.apk")
            assertNull(repository.findReusableApkArchive(installed, revision.repositoryId, baseOnly))
            root.writeJson(original.copy(apkEntries = baseOnly), manifestPath)
            assertEquals(File(revisionDir, "apk.tar").path, repository.findReusableApkArchive(installed, revision.repositoryId, baseOnly))
            assertNull(repository.findReusableApkArchive(installed, revision.repositoryId, baseOnly + "split_config.apk"))
            for (version in listOf(installed.packageInfo.copy(versionName = "2.0"), installed.packageInfo.copy(versionCode = 2))) {
                assertNull(repository.findReusableApkArchive(installed.copy(packageInfo = version), revision.repositoryId, baseOnly))
            }
            root.writeJson(original, manifestPath)
            val apk = File(revisionDir, "apk.tar")
            check(icon.delete())
            apk.writeText("damaged")
            val damaged = repository.inspectRevision(revision)
            check(damaged.canClean)
            assertEquals(emptyList<String>(), inspectedApks)
            val other = restore.copy(indexInfo = restore.indexInfo.copy(backupDir = "/other"))
            assertEquals(listOf(restore), repository.knownDamagedBackups(listOf(other, restore)))
            apk.writeBytes(byteArrayOf(1, 2, 3))
            val configFile = File(configPath)
            val configBytes = configFile.readBytes()
            configFile.appendText(" ")
            assertEquals(ConfigsPackageRestoreName, repository.inspectRevision(revision).issues.single().fileName)
            assertEquals(listOf(apk.path), inspectedApks)
            configFile.writeBytes(configBytes)
            icon.createNewFile()
            val repaired = repository.deleteFailedLocalBackups(listOf(damaged)) { _, _ -> }
            assertEquals(0, repaired.deletedCount)
            assertEquals(emptyList<PackageEntity>(), repository.knownDamagedBackups(listOf(restore)))
            assertEquals(BackupVerificationStatus.VALID, repaired.results.single().status)
            canList = false
            assertEquals(BackupVerificationStatus.VALID, repository.inspectRevision(revision).status)
            val verificationBeforeDisconnect = repository.verificationResults.value
            storageAvailable = false
            val expectedError = context.getString(com.xayah.core.data.R.string.backup_storage_unavailable, directory.path)
            assertEquals(expectedError, runCatching { repository.verifyAllLocal { _, _ -> } }.exceptionOrNull()?.message)
            assertEquals(expectedError, runCatching { repository.inspectRevision(revision) }.exceptionOrNull()?.message)
            assertEquals(verificationBeforeDisconnect, repository.verificationResults.value)
            storageAvailable = true
            val unreadable = repository.deleteFailedLocalBackups(listOf(damaged)) { _, _ -> }
            assertEquals(false, unreadable.results.single().canClean)
            assertEquals(0, unreadable.deletedCount)
            assertEquals(true, apk.exists())

            canList = true
            check(apk.delete() && apk.mkdir())
            val unexpected = File(apk, "personal.txt").apply { writeText("keep") }
            val wrongKind = repository.deleteFailedLocalBackups(listOf(damaged)) { _, _ -> }
            assertEquals(AppBackupRepository.VerificationIssueType.UNREADABLE, wrongKind.results.single().issues.single().type)
            assertEquals("keep", unexpected.readText())
            check(unexpected.delete() && apk.delete())
            assertEquals(AppBackupRepository.VerificationIssueType.FILE_MISSING, repository.inspectRevision(revision).issues.single().type)
            apk.writeBytes(byteArrayOf(1, 2, 3))
            unreadableFile = "apk.tar"
            assertEquals(false, repository.inspectRevision(revision).canClean)
            val verifiedRevision = revision.copy(sizeBytes = original.files.orEmpty().sumOf { it.sizeBytes })
            unreadableFile = ConfigsPackageRestoreName
            check(runCatching { repository.rebuildLocalIndex { _, _, _ -> } }.isFailure)
            assertEquals(listOf(verifiedRevision), database.appBackupDao().getRevisions(revision.repositoryId))
            unreadableFile = null
            dao.upsert(restore)
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_rebuild BEFORE DELETE ON backup_revisions BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            check(runCatching { repository.rebuildLocalIndex { _, _, _ -> } }.isFailure)
            assertEquals(restore, dao.queryById(restore.id))
            assertEquals(listOf(verifiedRevision), database.appBackupDao().getRevisions(revision.repositoryId))
            sql.execSQL("DROP TRIGGER fail_rebuild")
            val editedNote = runCatching {
                repository.rebuildLocalIndex { completed, _, _ ->
                    if (completed == 0) check(repository.updateRevisionNote(revision, "protected"))
                }
            }
            assertEquals(context.getString(com.xayah.core.data.R.string.rebuild_source_changed), editedNote.exceptionOrNull()?.message)
            assertEquals("protected", database.appBackupDao().getRevisions(revision.repositoryId).single().note)
            val revisions = database.appBackupDao()
            val newer = revision.copy(id = "newer", createdAt = 2000, artifactId = "com.example/user_0@2000")
            revisions.insertRevision(newer)
            assertEquals(listOf(newer.id, revision.id), repository.getLocalRetentionCandidates(1).map { it.revision.id })
            assertEquals(AppBackupRepository.RetentionResult.SKIPPED, repository.deleteOldRevision(revision.id, 1))
            val importer = TitaniumImportRepository(
                context, root, paths, repository, AppIconRepository(context, root, paths),
                LabelsRepo(context, kotlinx.coroutines.Dispatchers.Default, database),
            )
            val invalid = TitaniumImportRepository.BackupCandidate(
                metadataPath = "invalid.properties", backupDir = directory.path, packageName = revision.packageName,
                label = "Invalid", versionName = "1.0", versionCode = 1, createdAt = 1234, apkMd5 = null,
                hasData = true, imported = false, missingFiles = emptyList(), iconPath = null,
            )
            var result: TitaniumImportRepository.BackupResult? = null
            importer.importBackups(listOf(invalid)) { _, _, item -> result = item }
            assertEquals(TitaniumImportRepository.Status.FAILED, result?.status)
            assertEquals(false, repository.indexImportedRevision(directory.path, revisionDir.path))
            assertEquals(setOf(revision.id, newer.id), revisions.getRevisions(revision.repositoryId).map { it.id }.toSet())
            assertEquals("protected", revisions.getRevisions(revision.repositoryId).first { it.id == revision.id }.note)
            val manifest = checkNotNull(root.readJson<BackupManifest>(manifestPath))
            val different = manifest.copy(versionCode = manifest.versionCode + 1)
            check(root.writeJson(different, manifestPath).isSuccess)
            assertEquals(false, repository.updateRevisionNote(revision, "wrong revision"))
            assertEquals(different, root.readJson<BackupManifest>(manifestPath))
            check(root.writeJson(manifest, manifestPath).isSuccess)
            check(repository.updateRevisionNote(revision, ""))
            assertEquals(revision.id, revisions.getOldRevision(revision.id, revision.repositoryId, 1)?.id)
            revisions.deleteRevision(newer.id)
            assertEquals(AppBackupRepository.RetentionResult.SKIPPED, repository.deleteOldRevision(revision.id, 1))
            assertEquals(true, apk.exists())
            val secondRoot = File(directory, "second")
            val newerCopy = config.copy(createdAt = 5678, label = "Newer copy", versionCode = 2).toPackageEntity("", secondRoot.path)
            File(secondRoot, "apps/${newerCopy.archivesRelativeDir}").apply { mkdirs() }.resolve("apk.tar").writeBytes(byteArrayOf(4))
            repository.recordLegacyRevision(newerCopy, 1, 3)
            assertEquals(revision.id, repository.rebuildLocalIndex { _, _, _ -> }.single().revision.id)
            assertEquals("Newer copy", revisions.getApp(config.packageName, 0)?.label)
            assertEquals(true, repository.deleteRevision(revisions.getRevisions(":${secondRoot.path}").single()))
            assertEquals(config.label, revisions.getApp(config.packageName, 0)?.label)
            val selected = dao.queryRevisions(config.packageName, OpType.RESTORE, 0, config.createdAt).single()
            assertEquals(restore.id, selected.id)
            assertEquals(3L, selected.displayStats.apkBytes)
            val otherDirectory = File(directory, "other")
            val otherApk = File(otherDirectory, "apps/${restore.archivesRelativeDir}/apk.tar").apply {
                parentFile!!.mkdirs()
                writeText("keep")
            }
            context.saveBackupSavePath(otherDirectory.path)
            assertEquals(true, repository.deleteBackup(selected))
            assertEquals("keep", otherApk.readText())
            assertEquals(false, revisionDir.exists())
            assertEquals(emptyList<BackupRevisionEntity>(), revisions.getRevisions(revision.repositoryId))
            repository.inspectRevision(revision)
            assertEquals(false, revision.id in repository.verificationResults.value)
        } finally {
            context.saveBackupSavePath(previousPath)
            database.close()
            directory.deleteRecursively()
        }
    }
}
