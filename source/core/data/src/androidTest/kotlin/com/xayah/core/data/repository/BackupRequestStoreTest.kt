package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.datastore.saveBackupConfigs
import com.xayah.core.datastore.saveBackupItself
import com.xayah.core.model.CompressionType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRequestStoreTest {
    @Test
    fun temporaryOptionsUseCurrentDefaultsWithoutSavingChanges() = runBlocking {
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
            ).toPackageEntity("", "").apply { id = 1 }
            database.packageDao().upsert(app)
            val requests = BackupRequestStore(context, database.packageDao())
            for (defaults in listOf(BackupRequestStore.Options(true, false), BackupRequestStore.Options(false, true))) {
                context.saveBackupItself(defaults.backupItself)
                context.saveBackupConfigs(defaults.backupConfigs)
                requests.prepare(listOf(app.id))
                assertEquals(defaults, requests.options.value)

                val temporary = BackupRequestStore.Options(!defaults.backupItself, !defaults.backupConfigs)
                requests.setOptions(temporary)
                assertEquals(temporary, requests.options.value)
                assertEquals(defaults.backupItself, context.readBackupItself().first())
                assertEquals(defaults.backupConfigs, context.readBackupConfigs().first())

                requests.prepare(listOf(app.id))
                assertEquals(defaults, requests.options.value)
                requests.setOptions(temporary)
                val taskOptions = requests.options.value
                requests.clear()
                assertTrue(requests.packages.value.isEmpty())
                assertEquals(temporary, taskOptions)
                requests.prepare(listOf(app.id))
                assertEquals(defaults, requests.options.value)
            }
        } finally {
            context.saveBackupItself(originalItself)
            context.saveBackupConfigs(originalConfigs)
            database.close()
        }
    }
}
