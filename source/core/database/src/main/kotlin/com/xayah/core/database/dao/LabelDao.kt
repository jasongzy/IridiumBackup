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

private const val APP_REFS = "SELECT DISTINCT label, packageName, userId, 0 AS preserveId FROM LabelAppCrossRefEntity"

@Dao
interface LabelDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLabel(item: LabelEntity): Long

    @Upsert(entity = LabelEntity::class)
    suspend fun upsertLabels(items: List<LabelEntity>)

    @Query("""
        INSERT INTO LabelAppCrossRefEntity (label, packageName, userId, preserveId)
        SELECT :label, :packageName, :userId, 0 WHERE NOT EXISTS (
            SELECT 1 FROM LabelAppCrossRefEntity WHERE label = :label AND packageName = :packageName AND userId = :userId
        )
    """)
    suspend fun insertAppRef(label: String, packageName: String, userId: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingApps(items: List<BackupAppEntity>)

    @Transaction
    suspend fun upsertAppRefs(items: List<LabelAppCrossRefEntity>) {
        val labels = queryLabels().mapTo(mutableSetOf(), LabelEntity::label)
        val refs = items.filter { it.label in labels }
        insertMissingApps(refs.map { AppKey(it.packageName, it.userId) }.distinct().map(AppKey::toBackupApp))
        refs.forEach { insertAppRef(it.label, it.packageName, it.userId) }
    }

    @Upsert(entity = LabelFileCrossRefEntity::class)
    suspend fun upsert(item: LabelFileCrossRefEntity): Long

    @Upsert(entity = LabelFileCrossRefEntity::class)
    suspend fun upsertFileRefs(items: List<LabelFileCrossRefEntity>)

    @Query("SELECT * FROM LabelEntity ORDER BY label")
    fun queryLabelsFlow(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM LabelEntity ORDER BY label")
    suspend fun queryLabels(): List<LabelEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM LabelEntity WHERE label = :label)")
    suspend fun hasLabel(label: String): Boolean

    @Query("$APP_REFS ORDER BY label, packageName, userId")
    suspend fun queryAppRefs(): List<LabelAppCrossRefEntity>

    @Query("SELECT * FROM LabelFileCrossRefEntity ORDER BY label")
    suspend fun queryFileRefs(): List<LabelFileCrossRefEntity>

    @Query("$APP_REFS ORDER BY label, packageName, userId")
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

    @Query("UPDATE OR REPLACE LabelAppCrossRefEntity SET label = :newLabel WHERE label = :oldLabel")
    suspend fun renameAppRefs(oldLabel: String, newLabel: String)

    @Query("UPDATE OR REPLACE LabelFileCrossRefEntity SET label = :newLabel WHERE label = :oldLabel")
    suspend fun renameFileRefs(oldLabel: String, newLabel: String)

    @Transaction
    suspend fun rename(oldLabel: String, newLabel: String, allowMerge: Boolean): Boolean {
        if (!hasLabel(oldLabel)) return false
        if (insertLabel(LabelEntity(newLabel)) == -1L && !allowMerge) return false
        renameAppRefs(oldLabel, newLabel)
        renameFileRefs(oldLabel, newLabel)
        delete(oldLabel)
        return true
    }

    @Query("DELETE FROM LabelAppCrossRefEntity WHERE label = :label AND packageName = :packageName AND userId = :userId")
    suspend fun deleteAppRef(label: String, packageName: String, userId: Int)

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
