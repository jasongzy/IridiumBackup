package com.xayah.feature.main.restore

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryUpsertEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSourceTest {
    @Test
    fun selectingBackupDirectoriesPreservesRestoreEntries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousPath = context.readBackupSavePath().first()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val directories = database.directoryDao()
            val packages = database.packageDao()
            val repository = DirectoryRepository(context, directories, RemoteRootService(context))
            directories.upsert(listOf("first", "second").map { name ->
                DirectoryUpsertEntity(title = name, parent = context.cacheDir.path, child = name, storageType = StorageType.CUSTOM, active = true)
            })
            val choices = directories.queryActiveDirectories()
            val config = config()
            packages.upsert(choices.map { config.toPackageEntity("", it.path) })
            val expected = packages.queryRevisions(config.packageName, OpType.RESTORE, config.userId, config.createdAt)
            context.saveBackupSavePath(choices.first().path)
            for (choice in listOf(choices.first(), choices.last(), choices.first())) {
                repository.selectDir(choice)
                assertEquals(choice.path, context.readBackupSavePath().first())
                assertEquals(choice.id, directories.querySelectedByDirectoryType()?.id)
                assertEquals(expected.toSet(), packages.queryRevisions(config.packageName, OpType.RESTORE, config.userId, config.createdAt).toSet())
            }
        } finally {
            context.saveBackupSavePath(previousPath)
            database.close()
        }
    }

    @Test
    fun rebuildingLocalIndexPreservesOtherBackupSources() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val packages = database.packageDao()
            val config = config()
            packages.upsert(listOf(
                config.toPackageEntity("", "/backup"),
                config.toPackageEntity("cloud", "/backup"),
                config.toPackageEntity("", "/other"),
            ))
            val expected = packages.queryRevisions(config.packageName, OpType.RESTORE, config.userId, config.createdAt)
                .filter { it.indexInfo.cloud.isNotEmpty() || it.indexInfo.backupDir != "/backup" }
            packages.replaceLocalRestoreIndex("/backup", emptyList())
            assertEquals(expected.toSet(), packages.queryRevisions(config.packageName, OpType.RESTORE, config.userId, config.createdAt).toSet())
        } finally {
            database.close()
        }
    }

    private fun config() = PackageRestoreConfig(
        packageName = "com.example.app", userId = 0, createdAt = 1234,
        compressionType = CompressionType.ZSTD, label = "Example", versionName = "1.0", versionCode = 1,
        isSystemApp = false, hasKeystore = false, permissions = emptyList(), ssaid = "",
        dataStates = PackageDataStates(), dataStats = PackageDataStats(), displayStats = PackageDataStats(),
    )
}
