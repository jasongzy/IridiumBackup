package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupAppEntity
import com.xayah.core.model.toBackupApp
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {
    @Upsert(entity = LabelEntity::class)
    suspend fun upsert(item: LabelEntity): Long

    @Upsert(entity = LabelEntity::class)
    suspend fun upsertLabels(items: List<LabelEntity>)

    @Upsert(entity = LabelAppCrossRefEntity::class)
    suspend fun upsertAppRefRows(items: List<LabelAppCrossRefEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingApps(items: List<BackupAppEntity>)

    @Transaction
    suspend fun upsertAppRefs(items: List<LabelAppCrossRefEntity>) {
        insertMissingApps(items.map { AppKey(it.packageName, it.userId) }.distinct().map(AppKey::toBackupApp))
        upsertAppRefRows(items)
    }

    @Upsert(entity = LabelFileCrossRefEntity::class)
    suspend fun upsert(item: LabelFileCrossRefEntity): Long

    @Upsert(entity = LabelFileCrossRefEntity::class)
    suspend fun upsertFileRefs(items: List<LabelFileCrossRefEntity>)

    @Query("SELECT * FROM LabelEntity ORDER BY label")
    fun queryLabelsFlow(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM LabelEntity ORDER BY label")
    suspend fun queryLabels(): List<LabelEntity>

    @Query("SELECT * FROM LabelAppCrossRefEntity WHERE label in (:labels) ORDER BY label")
    suspend fun queryAppRefs(labels: Set<String>): List<LabelAppCrossRefEntity>

    @Query("SELECT * FROM LabelFileCrossRefEntity WHERE label in (:labels) ORDER BY label")
    suspend fun queryFileRefs(labels: Set<String>): List<LabelFileCrossRefEntity>

    @Query("SELECT * FROM LabelAppCrossRefEntity ORDER BY label")
    suspend fun queryAppRefs(): List<LabelAppCrossRefEntity>

    @Query("SELECT * FROM LabelFileCrossRefEntity ORDER BY label")
    suspend fun queryFileRefs(): List<LabelFileCrossRefEntity>

    @Query("SELECT * FROM LabelAppCrossRefEntity ORDER BY label")
    fun queryAppRefsFlow(): Flow<List<LabelAppCrossRefEntity>>

    @Query("SELECT * FROM LabelFileCrossRefEntity ORDER BY label")
    fun queryFileRefsFlow(): Flow<List<LabelFileCrossRefEntity>>

    @Query("DELETE FROM LabelEntity WHERE label = :label")
    suspend fun delete(label: String)

    @Query("DELETE FROM LabelAppCrossRefEntity WHERE label = :label")
    suspend fun deleteAppRefs(label: String)

    @Query("DELETE FROM LabelFileCrossRefEntity WHERE label = :label")
    suspend fun deleteFileRefs(label: String)

    @Transaction
    suspend fun deleteCompletely(label: String) {
        deleteAppRefs(label)
        deleteFileRefs(label)
        delete(label)
    }

    @Transaction
    suspend fun rename(oldLabel: String, newLabel: String) {
        if (oldLabel == newLabel) return
        upsert(LabelEntity(newLabel))
        upsertAppRefs(queryAppRefs(setOf(oldLabel)).map { it.copy(label = newLabel) })
        upsertFileRefs(queryFileRefs(setOf(oldLabel)).map { it.copy(label = newLabel) })
        deleteCompletely(oldLabel)
    }

    @Delete(entity = LabelAppCrossRefEntity::class)
    suspend fun deleteAppRef(item: LabelAppCrossRefEntity)

    @Delete(entity = LabelAppCrossRefEntity::class)
    suspend fun deleteAppRefs(items: List<LabelAppCrossRefEntity>)

    @Delete(entity = LabelFileCrossRefEntity::class)
    suspend fun deleteFileRef(item: LabelFileCrossRefEntity)

    @Query(
        "DELETE FROM LabelAppCrossRefEntity WHERE NOT EXISTS (" +
            "SELECT 1 FROM PackageEntity WHERE " +
            "PackageEntity.indexInfo_packageName = LabelAppCrossRefEntity.packageName AND " +
            "PackageEntity.indexInfo_userId = LabelAppCrossRefEntity.userId) AND NOT EXISTS (" +
            "SELECT 1 FROM backup_apps WHERE " +
            "backup_apps.packageName = LabelAppCrossRefEntity.packageName AND " +
            "backup_apps.userId = LabelAppCrossRefEntity.userId)"
    )
    suspend fun deleteOrphanedAppRefs()
}
