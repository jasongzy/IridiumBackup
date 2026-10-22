package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.AppKey
import com.xayah.core.model.AppNoteItem
import com.xayah.core.model.toBackupApp
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNotesImportTest {
    @Test
    fun importValidatesAllIdentitiesAndPreservesAppMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val root = RemoteRootService(context)
            val paths = PathUtil(context)
            val dao = database.appBackupDao()
            val repository = AppBackupRepository(
                context, dao,
                PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), database.packageDao(), paths),
                AppIconRepository(context, root, paths), XposedModuleDetector(context, root), root, paths,
            )
            val installed = AppKey("com.example.app", 0).toBackupApp().copy(
                label = "Example", versionName = "2.0", versionCode = 2,
                firstInstallTime = 1234, lastUpdateTime = 5678, isInstalled = true, note = "original",
            )
            val secondary = installed.copy(userId = 10, note = "secondary")
            dao.upsertApps(listOf(installed, secondary))
            val valid = AppNoteItem(installed.packageName, 0, " imported ")
            listOf(valid.copy(packageName = "../invalid"), valid.copy(userId = -1)).forEach { invalid ->
                val result = runCatching { repository.importAppNotes(listOf(valid, invalid)) }
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
                assertEquals(installed, dao.getApp(installed.packageName, 0))
            }

            val missing = AppKey("com.example.missing", 0).toBackupApp()
            repository.importAppNotes(listOf(valid, AppNoteItem(missing.packageName, 0, " retained ")))
            assertEquals(installed.copy(note = "imported"), dao.getApp(installed.packageName, 0))
            assertEquals(secondary, dao.getApp(installed.packageName, 10))
            assertEquals(missing.copy(note = "retained"), dao.getApp(missing.packageName, 0))

            repository.importAppNotes(listOf(valid.copy(note = " "), AppNoteItem("com.example.empty", 0, " ")))
            assertEquals(installed.copy(note = ""), dao.getApp(installed.packageName, 0))
            assertNull(dao.getApp("com.example.empty", 0))
        } finally {
            database.close()
        }
    }
}
