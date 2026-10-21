package com.xayah.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.toBackupApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRevisionTest {
    @Test
    fun sizeRefreshPreservesEditsAndDoesNotRecreateDeletedBackups() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.appBackupDao()
            val app = AppKey("com.example.app", 0).toBackupApp()
            dao.upsertApps(listOf(app))
            val revision = BackupRevisionEntity(app.packageName, 0, 1234, "1.0", 1, BackupEngine.LEGACY, ":/backup", "com.example.app/user_0@1234", 1)
            dao.upsertRevision(revision)
            dao.updateRevisionNote(revision.id, "protected")
            dao.updateRevisionSize(revision.id, 512)
            assertEquals(revision.copy(note = "protected", sizeBytes = 512), dao.getRevisions(revision.repositoryId).single())

            dao.deleteRevision(revision.id)
            dao.updateRevisionSize(revision.id, 1024)
            assertTrue(dao.getRevisions(revision.repositoryId).isEmpty())
        } finally {
            database.close()
        }
    }
}
