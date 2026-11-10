package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.AppKey
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.database.PackageDataStates.Companion.setSelected
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupRecordingTest {
    @Test
    fun completedBackupPreservesCurrentAppSettingsAndMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val backupDir = context.localBackupSaveDir()
            val root = RemoteRootService(context)
            val paths = PathUtil(context)
            val packages = database.packageDao()
            val dao = database.appBackupDao()
            val repository = AppBackupRepository(
                context, database,
                PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), packages, paths),
                AppIconRepository(context, root), XposedModuleDetector(context, root), root,
            )
            val app = PackageRestoreConfig(
                packageName = "com.example.backup", userId = 0, createdAt = 0, compressionType = CompressionType.ZSTD,
                label = "Example", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            ).toPackageEntity("", "").apply {
                id = 1
                indexInfo.opType = OpType.BACKUP
            }
            packages.upsert(app)
            val requests = PackageRequestStore(context, packages)
            val temporaryScope = DataType.packageArchives.fold(app.dataStates) { states, type ->
                type.setSelected(states, type == DataType.PACKAGE_USER)
            }
            requests.prepareBackup(listOf(app.id), mapOf(app.id to temporaryScope))
            val request = checkNotNull(requests.takeBackup()).packages.single()
            val current = app.copy(
                packageInfo = app.packageInfo.copy(versionName = "2.0", versionCode = 2),
                extraInfo = app.extraInfo.copy(blocked = true, enabled = false),
                dataStates = app.dataStates.copy(userState = DataState.NotSelected),
            )
            packages.upsert(current)
            repository.syncInstalledApps(0)
            repository.updateAppNote(app.packageName, 0, "Current note")
            val currentMetadata = dao.getApp(app.packageName, 0)

            val revision = request.copy(id = 0, indexInfo = request.indexInfo.copy(opType = OpType.RESTORE, backupDir = backupDir, preserveId = 1234))
            repository.recordLegacyRevision(revision, 2, 512)
            repository.recordLegacyRevision(revision, 2, 512)
            val stored = packages.queryRevisions(app.packageName, OpType.RESTORE, 0, 1234).single()

            assertEquals(currentMetadata, dao.getApp(app.packageName, 0))
            assertEquals(
                current.copy(extraInfo = current.extraInfo.copy(lastBackupTime = 1234)),
                packages.queryById(app.id),
            )
            assertEquals(temporaryScope, request.dataStates)
            val recorded = dao.getRevisions(":$backupDir").single()
            assertEquals(stored.copy(dataStates = temporaryScope), repository.revisionForRestore(recorded, temporaryScope))
            assertEquals(stored, packages.queryById(stored.id))
            assertEquals("1.0", recorded.appVersionName)
            assertEquals(1L, recorded.appVersionCode)
            assertEquals(2, recorded.contentMask)
            assertEquals(512L, recorded.sizeBytes)
            val partial = stored.copy(displayStats = PackageDataStats(userBytes = 400))
            assertEquals(512L, repository.getRestoreSizeBytes(recorded.repositoryId, listOf(partial)))
            dao.upsertRevisions(listOf(recorded.copy(contentMask = 3)))
            assertEquals(400L, repository.getRestoreSizeBytes(recorded.repositoryId, listOf(partial)))
            assertEquals(400L, repository.getRestoreSizeBytes("cloud:/backup", listOf(partial)))

            packages.deleteByIds(listOf(app.id))
            repository.syncInstalledApps(0)
            repository.recordLegacyRevision(
                revision.copy(indexInfo = revision.indexInfo.copy(preserveId = 2345), packageInfo = current.packageInfo), 2, 768,
            )
            assertNull(packages.queryById(app.id))
            val archived = checkNotNull(dao.getApp(app.packageName, 0))
            assertEquals(false, archived.isInstalled)
            assertEquals("2.0", archived.versionName)
            assertEquals("Current note", archived.note)
            assertEquals(0L, archived.lastUpdateTime)
            assertEquals(2, dao.getRevisions(":$backupDir").size)
            val profile = revision.copy(indexInfo = revision.indexInfo.copy(userId = 10))
            repository.recordLegacyRevision(profile, 1, 512)
            repository.recordLegacyRevision(revision.copy(indexInfo = revision.indexInfo.copy(cloud = "other", preserveId = 3456)), 1, 100)
            val keys = setOf(AppKey(app.packageName, 0), AppKey(app.packageName, 10))
            val latest = repository.latestLocalPackagesForRestore(keys)
            assertEquals(listOf(2345L, 1234L), latest.map { it.preserveId })
            assertEquals(listOf(2, 1), latest.map { it.selectionFlag })
            assertEquals(listOf(10), repository.latestLocalPackagesForRestore(setOf(AppKey(app.packageName, 10))).map { it.userId })
        } finally {
            database.close()
        }
    }
}
