package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.saveBackupConfigs
import com.xayah.core.datastore.saveBackupItself
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageRequestStoreTest {
    @Test
    fun preparedSelectionIsConsumedOnceWithItsOwnDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val originalItself = context.readBackupItself().first()
        val originalConfigs = context.readBackupConfigs().first()
        try {
            val app = PackageRestoreConfig(
                packageName = "com.example.backup", userId = 0, createdAt = 0, compressionType = CompressionType.ZSTD,
                label = "Example", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            ).toPackageEntity("", "").apply { id = 1; indexInfo.opType = OpType.BACKUP }
            database.packageDao().upsert(app)
            val requests = PackageRequestStore(context, database.packageDao())
            context.saveBackupItself(true)
            context.saveBackupConfigs(false)
            assertEquals(true, requests.prepareBackup((1001L downTo 1L).toList() + app.id))
            val first = checkNotNull(requests.takeBackup())
            assertNull(requests.takeBackup())
            assertEquals(listOf(app.id), first.packages.map { it.id })

            context.saveBackupItself(false)
            context.saveBackupConfigs(true)
            requests.prepareBackup(listOf(app.id))
            assertEquals(first.copy(backupItself = false, backupConfigs = true), requests.takeBackup())
            assertEquals(false, requests.prepareBackup(listOf(999)))
            assertNull(requests.takeBackup())
            assertEquals(true, first.backupItself)
            assertEquals(false, first.backupConfigs)
        } finally {
            context.saveBackupItself(originalItself)
            context.saveBackupConfigs(originalConfigs)
            database.close()
        }
    }
}
