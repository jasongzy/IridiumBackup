package com.xayah.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.AppKey
import com.xayah.core.model.toBackupApp
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
            dao.updateAppNote(primary.packageName, 0, "edited during scan")
            dao.replaceInstalledApps(0, listOf(primary.copy(versionName = "2.0")))
            assertEquals(primary.copy(versionName = "2.0", note = "edited during scan"), dao.getApp(primary.packageName, 0))
            assertEquals(secondary, dao.getApp(primary.packageName, 10))

            dao.updateAppNote(primary.packageName, 0, "")
            dao.upsertAppMetadata(listOf(primary))
            assertEquals("", dao.getApp(primary.packageName, 0)?.note)

            dao.replaceRepositoryIndex(":/backup", listOf(secondary.copy(note = "stale note")), emptyList())
            assertEquals(secondary, dao.getApp(primary.packageName, 10))
        } finally {
            database.close()
        }
    }
}
