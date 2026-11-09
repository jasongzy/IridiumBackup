package com.xayah.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryUpsertEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryStatsTest {
    @Test
    fun disconnectedVolumeKeepsItsSelectionAndRecoversOnReconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.directoryDao()
            val child = "IridiumBackup"
            val internal = "/storage/emulated/0"
            val card = "/mnt/media_rw/1234-ABCD"
            val volumes = mapOf(StorageType.INTERNAL to listOf(internal, "/storage/emulated/10"), StorageType.EXTERNAL to listOf(card))
            dao.refreshVolumes(volumes, child, emptyList())
            val external = dao.queryActiveDirectories().single { it.parent == card }
            dao.addCustomDirectory(card, child)
            dao.select(external.id)

            dao.refreshVolumes(mapOf(StorageType.INTERNAL to listOf(internal), StorageType.EXTERNAL to emptyList()), child, emptyList())
            assertEquals(listOf(internal), dao.queryActiveDirectories().map { it.parent })
            assertEquals(external.copy(selected = true, active = false), dao.visibleDirectories(StorageType.EXTERNAL).first().single())
            dao.refreshVolumes(volumes, child, emptyList())
            assertEquals(external.copy(selected = true), dao.querySelectedByDirectoryType())
        } finally {
            database.close()
        }
    }

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
            dao.updateStats(snapshot.id, null, 80, 100)
            dao.updateFileSystem(snapshot.id, "ext4", listOf("ext4"), "", true)
            val refreshed = dao.queryActiveDirectories().single { it.id == first.id }
            assertEquals(first.copy(childUsedBytes = 10, availableBytes = 80, totalBytes = 100, type = "ext4", tags = listOf("ext4")), refreshed)
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
