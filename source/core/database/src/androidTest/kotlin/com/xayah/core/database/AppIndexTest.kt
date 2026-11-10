package com.xayah.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.AppKey
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toBackupApp
import com.xayah.core.model.toPackageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIndexTest {
    @Test
    fun metadataUpdatesPreserveCurrentNotesForEachUser() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.appBackupDao()
            val primary = AppKey("com.example.app", 0).toBackupApp().copy(isInstalled = true, note = "old note")
            val secondary = primary.copy(userId = 10, note = "secondary note")
            dao.upsertApps(listOf(primary, secondary))
            val scan = PackageRestoreConfig(
                packageName = primary.packageName, userId = 0, createdAt = 0, compressionType = CompressionType.TAR,
                label = primary.label, versionName = "2.0", versionCode = 0, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            ).toPackageEntity("", "").apply { indexInfo.opType = OpType.BACKUP }
            database.packageDao().upsert(scan)
            dao.updateAppNote(primary.packageName, 0, "edited during scan")
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE writes (name TEXT)")
            sql.execSQL("CREATE TRIGGER count_app_changes AFTER UPDATE ON backup_apps BEGIN INSERT INTO writes VALUES (NEW.packageName); END")
            sql.execSQL("CREATE TRIGGER count_time_changes AFTER UPDATE OF extraInfo_lastBackupTime ON PackageEntity BEGIN INSERT INTO writes VALUES (NEW.indexInfo_packageName); END")
            dao.syncInstalledApps(0, primary.packageName)
            dao.syncInstalledApps(0)
            sql.query("SELECT COUNT(*) FROM writes").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            assertEquals(primary.copy(versionName = "2.0", note = "edited during scan"), dao.getApp(primary.packageName, 0))
            assertEquals(secondary, dao.getApp(primary.packageName, 10))

            dao.updateAppNote(primary.packageName, 0, "")
            dao.syncInstalledApps(0)
            assertEquals("", dao.getApp(primary.packageName, 0)?.note)

            dao.replaceRepositoryIndex(":/backup", listOf(secondary.copy(note = "stale note")), emptyList())
            assertEquals(secondary, dao.getApp(primary.packageName, 10))
        } finally {
            database.close()
        }
    }
}
