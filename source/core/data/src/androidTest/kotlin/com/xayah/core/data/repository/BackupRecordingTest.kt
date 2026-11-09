package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
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
            val root = RemoteRootService(context)
            val paths = PathUtil(context)
            val packages = database.packageDao()
            val dao = database.appBackupDao()
            val repository = AppBackupRepository(
                context, database,
                PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), packages, paths),
                AppIconRepository(context, root, paths), XposedModuleDetector(context, root), root,
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
            val requests = BackupRequestStore(context, packages)
            val temporaryScope = DataType.packageArchives.fold(app.dataStates) { states, type ->
                type.setSelected(states, type == DataType.PACKAGE_USER)
            }
            requests.prepare(listOf(app.id), mapOf(app.id to temporaryScope))
            val request = checkNotNull(requests.take()).packages.single()
            val current = app.copy(
                packageInfo = app.packageInfo.copy(versionName = "2.0", versionCode = 2),
                extraInfo = app.extraInfo.copy(blocked = true, enabled = false),
                dataStates = app.dataStates.copy(userState = DataState.NotSelected),
            )
            packages.upsert(current)
            repository.syncInstalledApps(0, listOf(current))
            repository.updateAppNote(app.packageName, 0, "Current note")
            val currentMetadata = dao.getApp(app.packageName, 0)

            val revision = request.copy(id = 0, indexInfo = request.indexInfo.copy(opType = OpType.RESTORE, backupDir = "/backup", preserveId = 1234))
            repository.recordLegacyRevision(revision, 2, 512)
            repository.recordLegacyRevision(revision, 2, 512)
            val stored = packages.queryRevisions(app.packageName, OpType.RESTORE, 0, 1234).single()

            assertEquals(currentMetadata, dao.getApp(app.packageName, 0))
            assertEquals(
                current.copy(extraInfo = current.extraInfo.copy(lastBackupTime = 1234)),
                packages.queryById(app.id),
            )
            assertEquals(temporaryScope, request.dataStates)
            val recorded = dao.getRevisions(":/backup").single()
            repository.selectRevisionForRestore(recorded, temporaryScope)
            assertEquals(stored.copy(dataStates = temporaryScope, extraInfo = stored.extraInfo.copy(activated = true)), packages.queryById(stored.id))
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
            repository.syncInstalledApps(0, emptyList())
            val uninstalled = dao.getApp(app.packageName, 0)
            repository.recordLegacyRevision(
                revision.copy(indexInfo = revision.indexInfo.copy(preserveId = 2345)), 2, 768,
            )
            assertNull(packages.queryById(app.id))
            assertEquals(uninstalled, dao.getApp(app.packageName, 0))
            assertEquals(2, dao.getRevisions(":/backup").size)
        } finally {
            database.close()
        }
    }
}
