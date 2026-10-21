package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
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
                context, dao,
                PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), packages, paths),
                AppIconRepository(context, root, paths), XposedModuleDetector(context, root), root, paths,
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
            val requests = BackupRequestStore(packages)
            val temporaryScope = app.dataStates.copy(apkState = DataState.NotSelected)
            requests.prepare(listOf(app.id), mapOf(app.id to temporaryScope))
            val request = requests.packages.value.single()
            val current = app.copy(
                packageInfo = app.packageInfo.copy(versionName = "2.0", versionCode = 2),
                extraInfo = app.extraInfo.copy(blocked = true, enabled = false),
                dataStates = app.dataStates.copy(userState = DataState.NotSelected),
            )
            packages.upsert(current)
            repository.syncInstalledApps(0, listOf(current))
            repository.updateAppNote(app.packageName, 0, "Current note")
            val currentMetadata = dao.getApp(app.packageName, 0)

            val revision = request.copy(indexInfo = request.indexInfo.copy(preserveId = 1234))
            repository.recordLegacyRevision(revision, 1234, ":/backup", 2, 512)

            assertEquals(currentMetadata, dao.getApp(app.packageName, 0))
            assertEquals(
                current.copy(extraInfo = current.extraInfo.copy(lastBackupTime = 1234)),
                packages.queryById(app.id),
            )
            assertEquals(temporaryScope, request.dataStates)
            val recorded = dao.getRevisions(":/backup").single()
            assertEquals("1.0", recorded.appVersionName)
            assertEquals(1L, recorded.appVersionCode)
            assertEquals(2, recorded.contentMask)
            assertEquals(512L, recorded.sizeBytes)

            packages.deleteByIds(listOf(app.id))
            repository.syncInstalledApps(0, emptyList())
            val uninstalled = dao.getApp(app.packageName, 0)
            repository.recordLegacyRevision(
                request.copy(indexInfo = request.indexInfo.copy(preserveId = 2345)), 2345, ":/backup", 2, 768,
            )
            assertNull(packages.queryById(app.id))
            assertEquals(uninstalled, dao.getApp(app.packageName, 0))
            assertEquals(2, dao.getRevisions(":/backup").size)
        } finally {
            database.close()
        }
    }
}
