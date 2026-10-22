package com.xayah.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryUpsertEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryStatsTest {
    @Test
    fun refreshPreservesSelectionAndDoesNotReinsertDeletedDirectories() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.directoryDao()
            dao.upsert(listOf("first", "second").map {
                DirectoryUpsertEntity(title = it, parent = "/backup", child = it, storageType = StorageType.CUSTOM, active = true)
            })
            val (first, second) = dao.queryActiveDirectories()
            dao.select(first.id)
            val snapshot = dao.querySelectedByDirectoryType()!!
            dao.select(second.id)
            dao.updateStats(snapshot.id, 10, 90, 100)
            dao.updateFileSystem(snapshot.id, "ext4", listOf("ext4"), "", true)
            val refreshed = dao.queryActiveDirectories().single { it.id == first.id }
            assertEquals(first.copy(childUsedBytes = 10, availableBytes = 90, totalBytes = 100, type = "ext4", tags = listOf("ext4")), refreshed)
            assertEquals(second.id, dao.querySelectedByDirectoryType()?.id)

            dao.delete(first)
            dao.updateStats(snapshot.id, 20, 80, 100)
            dao.updateFileSystem(snapshot.id, "ext4", listOf("ext4"), "", true)
            assertEquals(listOf(second.copy(selected = true)), dao.queryActiveDirectories())
        } finally {
            database.close()
        }
    }
}
