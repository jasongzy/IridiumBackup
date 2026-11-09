package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.model.database.DirectoryUpsertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectoryDao {
    @Query("SELECT COUNT(*) FROM DirectoryEntity")
    suspend fun count(): Int

    @Upsert(entity = DirectoryEntity::class)
    suspend fun upsert(items: List<DirectoryUpsertEntity>)

    @Query("UPDATE DirectoryEntity SET childUsedBytes = COALESCE(:childUsedBytes, childUsedBytes), availableBytes = :availableBytes, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateStats(id: Long, childUsedBytes: Long?, availableBytes: Long, totalBytes: Long)

    @Query("UPDATE DirectoryEntity SET type = :type, tags = :tags, error = :error, enabled = :enabled WHERE id = :id")
    suspend fun updateFileSystem(id: Long, type: String, tags: List<String>, error: String, enabled: Boolean)

    @Query("SELECT * FROM DirectoryEntity WHERE (active = 1 OR selected = 1 OR storageType = 'CUSTOM') AND storageType = :storageType ORDER BY parent")
    fun visibleDirectories(storageType: StorageType): Flow<List<DirectoryEntity>>

    @Query("SELECT * FROM DirectoryEntity WHERE active = 1 ORDER BY parent")
    suspend fun queryActiveDirectories(): List<DirectoryEntity>

    @Query("SELECT * FROM DirectoryEntity WHERE selected = 1 LIMIT 1")
    suspend fun querySelectedByDirectoryType(): DirectoryEntity?

    @Query("SELECT * FROM DirectoryEntity WHERE selected = 1 LIMIT 1")
    fun querySelectedByDirectoryTypeFlow(): Flow<DirectoryEntity?>

    @Query("SELECT id FROM DirectoryEntity WHERE parent = :parent AND child = :child LIMIT 1")
    suspend fun queryId(parent: String, child: String): Long?

    @Query("UPDATE DirectoryEntity SET active = 0 WHERE storageType = :type")
    suspend fun markUnavailable(type: StorageType)

    @Query("SELECT * FROM DirectoryEntity WHERE storageType = 'CUSTOM'")
    suspend fun customDirectories(): List<DirectoryEntity>

    @Query("UPDATE DirectoryEntity SET active = id NOT IN (:unavailable) WHERE storageType = 'CUSTOM'")
    suspend fun updateCustomAvailability(unavailable: List<Long>)

    @Transaction
    suspend fun refreshVolumes(volumes: Map<StorageType, List<String>>, child: String, unavailableCustom: List<Long>) {
        updateCustomAvailability(unavailableCustom)
        deleteDuplicates()
        volumes.forEach { (type, parents) ->
            markUnavailable(type)
            upsert(parents.distinct().map { parent ->
                DirectoryUpsertEntity(queryId(parent, child) ?: 0, "", parent, child, type, active = true)
            })
        }
    }

    @Transaction
    suspend fun addCustomDirectory(parent: String, child: String) {
        if (queryId(parent, child) == null) {
            upsert(listOf(DirectoryUpsertEntity(title = "", parent = parent, child = child, storageType = StorageType.CUSTOM, active = true)))
        }
    }

    @Query("UPDATE DirectoryEntity SET selected = (id = :id) WHERE EXISTS (SELECT 1 FROM DirectoryEntity WHERE id = :id AND active = 1)")
    suspend fun select(id: Long): Int

    @Query(
        "DELETE FROM DirectoryEntity WHERE id NOT IN (" +
            "SELECT COALESCE(MAX(CASE WHEN selected = 1 THEN id END), MIN(id)) " +
            "FROM DirectoryEntity GROUP BY parent, child)"
    )
    suspend fun deleteDuplicates()

    @Delete(entity = DirectoryEntity::class)
    suspend fun delete(item: DirectoryEntity)
}
