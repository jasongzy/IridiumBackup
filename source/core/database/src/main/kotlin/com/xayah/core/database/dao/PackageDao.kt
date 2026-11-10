package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.xayah.core.database.SQLITE_BIND_LIMIT
import com.xayah.core.model.AppKey
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageDataStatesEntity
import com.xayah.core.model.database.PackageDisplayStatsEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageUpdateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PackageDao {
    @Transaction
    suspend fun replaceLocalRestoreIndex(backupDir: String, items: List<PackageEntity>) {
        val stored = queryPackages(OpType.RESTORE, "", backupDir).associateBy { it.archivesRelativeDir to it.indexInfo.compressionType }
        delete(backupDir, OpType.RESTORE, cloud = "")
        upsert(items.map { it.withRestoreState(stored[it.archivesRelativeDir to it.indexInfo.compressionType]) })
    }

    @Transaction
    suspend fun applyRestoreScan(additions: List<PackageEntity>, missing: List<Long>) {
        for (app in additions) {
            val info = app.indexInfo
            if (query(app.packageName, info.opType, app.userId, app.preserveId, info.compressionType, info.cloud, info.backupDir) == null) upsert(app)
        }
        missing.chunked(SQLITE_BIND_LIMIT).forEach { deleteByIds(it) }
    }

    @Upsert(entity = PackageEntity::class)
    suspend fun upsert(items: List<PackageEntity>)

    @Upsert(entity = PackageEntity::class)
    suspend fun upsert(item: PackageEntity)

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId" +
                " AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir" +
                " LIMIT 1"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int, preserveId: Long, cloud: String, backupDir: String): PackageEntity?

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId" +
                " LIMIT 1"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int): PackageEntity?

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType" +
                " AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId"
    )
    suspend fun queryRevisions(packageName: String, opType: OpType, userId: Int, preserveId: Long): List<PackageEntity>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId" +
                " AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int, cloud: String, backupDir: String): List<PackageEntity>

    @Query("""
        SELECT DISTINCT indexInfo_packageName AS packageName, indexInfo_userId AS userId
        FROM PackageEntity
        WHERE indexInfo_opType = :opType AND extraInfo_blocked = 0 AND packageInfo_isXposedModule = 1
    """)
    fun observeXposedAppKeys(opType: OpType): Flow<List<AppKey>>

    @Query("UPDATE PackageEntity SET packageInfo_isXposedModule = :isXposedModule WHERE id = :id")
    suspend fun updateXposedModule(id: Long, isXposedModule: Boolean)

    @Query(
        "UPDATE PackageEntity SET packageInfo_isXposedModule = :isXposedModule WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = 'RESTORE' AND indexInfo_userId = :userId" +
                " AND indexInfo_preserveId = :preserveId AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    suspend fun updateRevisionXposedModule(
        packageName: String,
        userId: Int,
        preserveId: Long,
        cloud: String,
        backupDir: String,
        isXposedModule: Boolean,
    ): Int

    @Query(
        "SELECT indexInfo_packageName FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId = :userId" +
                " AND extraInfo_blocked = 0"
    )
    suspend fun queryPackageNamesByUserId(opType: OpType, userId: Int): List<String>

    @Query(
        "SELECT DISTINCT indexInfo_packageName FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId = :userId"
    )
    suspend fun queryPkgSetByUserId(opType: OpType, userId: Int): List<String>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_userId = :userId"
    )
    suspend fun queryPkgEntitiesByUserId(opType: OpType, userId: Int): List<PackageEntity>

    @Query("SELECT * FROM PackageEntity WHERE indexInfo_opType = :opType AND extraInfo_firstUpdated = :firstUpdated")
    suspend fun queryFirstUpdatedApps(opType: OpType, firstUpdated: Boolean): List<PackageEntity>

    @Query(
        "DELETE FROM PackageEntity WHERE indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_packageName in (:packageNames)"
    )
    suspend fun deletePackageChunk(opType: OpType, userId: Int, packageNames: List<String>)

    @Transaction
    suspend fun deleteByPkgNames(opType: OpType, userId: Int, packageNames: Set<String>) {
        packageNames.chunked(SQLITE_BIND_LIMIT - 2).forEach { deletePackageChunk(opType, userId, it) }
    }

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId AND indexInfo_compressionType = :ct" +
                " AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir" +
                " LIMIT 1"
    )
    suspend fun query(packageName: String, opType: OpType, userId: Int, preserveId: Long, ct: CompressionType, cloud: String, backupDir: String): PackageEntity?

    @Transaction
    suspend fun setBlocked(ids: Collection<Long>, blocked: Boolean): Int =
        ids.chunked(SQLITE_BIND_LIMIT - 1).sumOf { setBlockedChunk(it, blocked) }

    @Query("UPDATE PackageEntity SET extraInfo_activated = 0, extraInfo_blocked = :blocked WHERE id IN (:ids)")
    suspend fun setBlockedChunk(ids: List<Long>, blocked: Boolean): Int

    @Query("DELETE FROM PackageEntity WHERE id in (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM PackageEntity WHERE id = :id")
    suspend fun queryById(id: Long): PackageEntity?

    @Query("SELECT * FROM PackageEntity WHERE id IN (:ids)")
    suspend fun queryByIds(ids: List<Long>): List<PackageEntity>

    @Query("SELECT * FROM PackageEntity WHERE id = :id")
    fun queryFlowById(id: Long): Flow<PackageEntity?>

    @Update(PackageEntity::class)
    suspend fun updatePackageDataStates(items: List<PackageDataStatesEntity>)

    @Update(PackageEntity::class)
    suspend fun updateDisplayStats(item: PackageDisplayStatsEntity)

    @Update(PackageEntity::class)
    suspend fun update(items: List<PackageUpdateEntity>)

    @Update(PackageEntity::class)
    suspend fun update(item: PackageUpdateEntity)

    @Update(PackageEntity::class)
    suspend fun update(item: PackageEntity)

    @Query(
        "UPDATE PackageEntity" +
                " SET dataStates_apkState = :apk," +
                " dataStates_userState = :user," +
                " dataStates_userDeState = :userDe," +
                " dataStates_dataState = :data," +
                " dataStates_obbState = :obb," +
                " dataStates_mediaState = :media" +
                " WHERE id = :id"
    )
    suspend fun selectDataItemsById(id: Long, apk: String, user: String, userDe: String, data: String, obb: String, media: String)

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_packageName = :packageName AND indexInfo_opType = :opType AND indexInfo_userId = :userId AND indexInfo_preserveId = :preserveId" +
                " LIMIT 1"
    )
    fun queryFlow(packageName: String, opType: OpType, userId: Int, preserveId: Long): Flow<PackageEntity?>

    @Query("""
        SELECT indexInfo_userId FROM PackageEntity WHERE indexInfo_opType = :opType
        UNION SELECT userId FROM backup_apps WHERE isInstalled = 1
    """)
    suspend fun getCachedInstalledUserIds(opType: OpType): List<Int>

    @Query(
        "SELECT * FROM PackageEntity WHERE id = :id"
    )
    fun queryPackageFlow(id: Long): Flow<PackageEntity?>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked"
    )
    fun queryPackagesFlow(opType: OpType, blocked: Boolean): Flow<List<PackageEntity>>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND extraInfo_blocked = :blocked"
    )
    suspend fun queryPackages(opType: OpType, blocked: Boolean): List<PackageEntity>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType"
    )
    fun queryPackagesFlow(opType: OpType): Flow<List<PackageEntity>>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    fun queryPackagesFlow(opType: OpType, cloud: String, backupDir: String): Flow<List<PackageEntity>>

    @Query(
        "SELECT DISTINCT indexInfo_userId FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType"
    )
    suspend fun queryUserIds(opType: OpType): List<Int>

    @Query(
        "SELECT DISTINCT indexInfo_userId FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType"
    )
    fun queryUserIdsFlow(opType: OpType): Flow<List<Int>>

    @Query(
        "SELECT * FROM PackageEntity WHERE" +
                " indexInfo_opType = :opType AND indexInfo_cloud = :cloud AND indexInfo_backupDir = :backupDir"
    )
    suspend fun queryPackages(opType: OpType, cloud: String, backupDir: String): List<PackageEntity>

    @Query(
        "UPDATE PackageEntity" +
                " SET extraInfo_enabled = :enabled" +
                " WHERE id = :id"
    )
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query(
        "UPDATE PackageEntity SET extraInfo_blocked = 0 WHERE indexInfo_opType = :opType"
    )
    suspend fun clearBlocked(opType: OpType)

    @Query("DELETE FROM PackageEntity WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM PackageEntity WHERE indexInfo_backupDir = :backupDir AND indexInfo_opType = :opType AND indexInfo_cloud = :cloud")
    suspend fun delete(backupDir: String, opType: OpType, cloud: String)
}
