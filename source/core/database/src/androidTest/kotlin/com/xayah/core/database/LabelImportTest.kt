package com.xayah.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.AppKey
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.toBackupApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabelImportTest {
    @Test
    fun importedLabelsSurviveCleanupAndPreserveExistingApps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val existing = AppKey("com.example.installed", 0).toBackupApp()
                .copy(label = "Installed app", isInstalled = true, note = "Personal note")
            database.appBackupDao().upsertApps(listOf(existing))
            val refs = listOf(
                LabelAppCrossRefEntity("tag", existing.packageName, 0),
                LabelAppCrossRefEntity("tag", "com.example.uninstalled", 10),
            )
            val labels = database.labelDao()
            labels.upsertLabels(listOf(LabelEntity("tag")))
            labels.upsertAppRefs(refs)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE LabelAppCrossRefEntity SET preserveId = 1234 WHERE packageName = ?", arrayOf(refs.last().packageName),
            )
            labels.upsertAppRefs(refs)
            database.appBackupDao().deleteUninstalledAppsWithoutRevisions(10)
            database.appBackupDao().deleteUninstalledAppsWithoutRevisions()
            labels.deleteOrphanedAppRefs()

            assertEquals(refs.toSet(), labels.queryAppRefs().toSet())
            assertEquals(existing, database.appBackupDao().getApp(existing.packageName, 0))
            val imported = checkNotNull(database.appBackupDao().getApp("com.example.uninstalled", 10))
            assertFalse(imported.isInstalled)
            assertEquals("com.example.uninstalled", imported.label)
            labels.deleteAppRef("tag", imported.packageName, imported.userId)
            assertEquals(listOf(refs.first()), labels.queryAppRefs())

            labels.upsertLabels(listOf(LabelEntity("work")))
            labels.upsertAppRefs(listOf(refs.last().copy(label = "work")))
            assertFalse(labels.rename("tag", "work", allowMerge = false))
            assertEquals(true, labels.rename("tag", "work", allowMerge = true))
            assertEquals(refs.map { it.copy(label = "work") }.toSet(), labels.queryAppRefs().toSet())
        } finally {
            database.close()
        }
    }
}
