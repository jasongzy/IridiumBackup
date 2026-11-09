package com.xayah.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.xayah.core.model.AppBackupOverview
import com.xayah.core.model.AppKey
import com.xayah.core.model.AppNoteItem
import com.xayah.core.model.BackupAppEntity
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.OpType
import com.xayah.core.model.toBackupApp
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBackupDao {
    @Transaction
    suspend fun replaceInstalledApps(userId: Int, apps: List<BackupAppEntity>) {
        markUserAppsUninstalled(userId)
        upsertAppMetadata(apps)
        deleteUninstalledAppsWithoutRevisions(userId)
        syncLastBackupTimes(OpType.BACKUP)
    }

    @Upsert
    suspend fun upsertApps(apps: List<BackupAppEntity>)

    @Transaction
    suspend fun upsertAppMetadata(apps: List<BackupAppEntity>) {
        val notes = getAppsWithNotes().associate { AppKey(it.packageName, it.userId) to it.note }
        upsertApps(apps.map { it.copy(note = notes[AppKey(it.packageName, it.userId)].orEmpty()) })
    }

    @Query("UPDATE backup_apps SET note = :note WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateAppNote(packageName: String, userId: Int, note: String)

    @Transaction
    suspend fun importAppNotes(notes: List<AppNoteItem>) {
        notes.forEach { item ->
            val note = item.note.trim()
            if (note.isNotEmpty()) {
                insertMissingApp(AppKey(item.packageName, item.userId).toBackupApp().copy(note = note))
            }
            updateAppNote(item.packageName, item.userId, note)
        }
    }

    @Query("SELECT * FROM backup_apps WHERE note != ''")
    suspend fun getAppsWithNotes(): List<BackupAppEntity>

    @Query("SELECT COUNT(*) FROM backup_apps WHERE note != ''")
    fun observeAppNoteCount(): Flow<Int>

    @Query("UPDATE backup_apps SET isInstalled = 0, firstInstallTime = 0, lastUpdateTime = 0 WHERE userId = :userId")
    suspend fun markUserAppsUninstalled(userId: Int)

    @Query(
        """
        DELETE FROM backup_apps
        WHERE userId = :userId AND isInstalled = 0 AND note = ''
            AND NOT EXISTS (
                SELECT 1 FROM backup_revisions
                WHERE backup_revisions.packageName = backup_apps.packageName
                    AND backup_revisions.userId = backup_apps.userId
            )
            AND NOT EXISTS (
                SELECT 1 FROM LabelAppCrossRefEntity
                WHERE LabelAppCrossRefEntity.packageName = backup_apps.packageName
                    AND LabelAppCrossRefEntity.userId = backup_apps.userId
                    AND LabelAppCrossRefEntity.preserveId = 0
            )
        """
    )
    suspend fun deleteUninstalledAppsWithoutRevisions(userId: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevision(revision: BackupRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingApp(app: BackupAppEntity)

    @Transaction
    suspend fun recordRevision(app: BackupAppEntity, revision: BackupRevisionEntity) {
        insertMissingApp(app)
        insertRevision(revision)
        syncLastBackupTime(app.packageName, app.userId, OpType.BACKUP)
    }

    @Upsert
    suspend fun upsertRevisions(revisions: List<BackupRevisionEntity>)

    @Query("UPDATE backup_revisions SET note = :note WHERE revisionId = :revisionId")
    suspend fun updateRevisionNote(revisionId: String, note: String)

    @Query("UPDATE backup_revisions SET sizeBytes = :sizeBytes WHERE revisionId = :revisionId")
    suspend fun updateRevisionSize(revisionId: String, sizeBytes: Long)

    @Transaction
    suspend fun replaceRepositoryIndex(
        repositoryId: String,
        apps: List<BackupAppEntity>,
        revisions: List<BackupRevisionEntity>,
    ) {
        deleteRevisions(repositoryId)
        upsertAppMetadata(apps)
        upsertRevisions(revisions)
        syncLastBackupTimes(OpType.BACKUP)
    }

    @Query("SELECT * FROM backup_revisions WHERE packageName = :packageName AND userId = :userId AND repositoryId = :repositoryId AND (contentMask & 1) != 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestApkRevision(packageName: String, userId: Int, repositoryId: String): BackupRevisionEntity?

    @Query("SELECT * FROM backup_revisions WHERE repositoryId = :repositoryId ORDER BY createdAt DESC, revisionId")
    suspend fun getRevisions(repositoryId: String): List<BackupRevisionEntity>

    @Query("""
        SELECT r.*, COALESCE(a.label, r.packageName) AS appLabel
        FROM backup_revisions r LEFT JOIN backup_apps a ON a.packageName = r.packageName AND a.userId = r.userId
        WHERE r.repositoryId = :repositoryId
    """)
    suspend fun getRevisionLabels(repositoryId: String): Map<BackupRevisionEntity, @MapColumn(columnName = "appLabel") String>

    @Query(
        """
        SELECT * FROM backup_revisions AS candidate
        WHERE revisionId = :id AND repositoryId = :repositoryId AND (
            SELECT COUNT(*) FROM backup_revisions AS newer
            WHERE newer.repositoryId = candidate.repositoryId AND newer.packageName = candidate.packageName
                AND newer.userId = candidate.userId AND (newer.createdAt > candidate.createdAt
                    OR (newer.createdAt = candidate.createdAt AND newer.revisionId < candidate.revisionId))
        ) >= :keep
        """
    )
    suspend fun getOldRevision(id: String, repositoryId: String, keep: Int): BackupRevisionEntity?

    @Query("SELECT COUNT(*) FROM backup_revisions WHERE repositoryId = :repositoryId")
    suspend fun countRevisions(repositoryId: String): Int

    @Query("SELECT * FROM backup_revisions WHERE repositoryId = :repositoryId AND artifactId IN (:artifactIds)")
    suspend fun getRevisions(repositoryId: String, artifactIds: List<String>): List<BackupRevisionEntity>

    @Query("SELECT * FROM backup_revisions")
    fun observeRevisions(): Flow<List<BackupRevisionEntity>>

    @Query("DELETE FROM backup_revisions WHERE repositoryId = :repositoryId")
    suspend fun deleteRevisions(repositoryId: String)

    @Query("DELETE FROM backup_revisions WHERE revisionId = :revisionId")
    suspend fun deleteRevision(revisionId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM backup_revisions WHERE revisionId = :revisionId)")
    suspend fun hasRevision(revisionId: String): Boolean

    @Transaction
    suspend fun deleteRevisionAndSync(revision: BackupRevisionEntity) {
        deleteRevision(revision.id)
        syncLastBackupTime(revision.packageName, revision.userId, OpType.BACKUP)
    }

    @Query(
        """
        UPDATE PackageEntity
        SET extraInfo_lastBackupTime = COALESCE((
            SELECT MAX(backup_revisions.createdAt)
            FROM backup_revisions
            WHERE backup_revisions.packageName = PackageEntity.indexInfo_packageName
                AND backup_revisions.userId = PackageEntity.indexInfo_userId
        ), 0)
        WHERE indexInfo_opType = :opType
            AND indexInfo_packageName = :packageName
            AND indexInfo_userId = :userId
        """
    )
    suspend fun syncLastBackupTime(packageName: String, userId: Int, opType: OpType)

    @Query(
        """
        UPDATE PackageEntity
        SET extraInfo_lastBackupTime = COALESCE((
            SELECT MAX(backup_revisions.createdAt)
            FROM backup_revisions
            WHERE backup_revisions.packageName = PackageEntity.indexInfo_packageName
                AND backup_revisions.userId = PackageEntity.indexInfo_userId
        ), 0)
        WHERE indexInfo_opType = :opType
        """
    )
    suspend fun syncLastBackupTimes(opType: OpType)

    @Query(
        """
        DELETE FROM backup_apps
        WHERE isInstalled = 0 AND note = ''
            AND NOT EXISTS (
                SELECT 1 FROM backup_revisions
                WHERE backup_revisions.packageName = backup_apps.packageName
                    AND backup_revisions.userId = backup_apps.userId
            )
            AND NOT EXISTS (
                SELECT 1 FROM LabelAppCrossRefEntity
                WHERE LabelAppCrossRefEntity.packageName = backup_apps.packageName
                    AND LabelAppCrossRefEntity.userId = backup_apps.userId
                    AND LabelAppCrossRefEntity.preserveId = 0
            )
        """
    )
    suspend fun deleteUninstalledAppsWithoutRevisions()

    @Query(
        """
        SELECT backup_apps.*, COUNT(backup_revisions.revisionId) AS revisionCount,
            MAX(backup_revisions.createdAt) AS latestRevisionAt,
            MAX(CASE WHEN (backup_revisions.contentMask & 1) != 0 THEN 1 ELSE 0 END) AS hasApkBackup,
            MAX(CASE WHEN (backup_revisions.contentMask & 62) != 0 THEN 1 ELSE 0 END) AS hasDataBackup,
            MAX(CASE WHEN (backup_revisions.contentMask & 1) != 0
                AND backup_revisions.appVersionName = backup_apps.versionName
                AND backup_revisions.appVersionCode = backup_apps.versionCode THEN 1 ELSE 0 END) AS hasMatchingApkBackup,
            COALESCE(GROUP_CONCAT(backup_revisions.note, char(10)), '') AS revisionNotes
        FROM backup_apps
        LEFT JOIN backup_revisions
            ON backup_apps.packageName = backup_revisions.packageName
            AND backup_apps.userId = backup_revisions.userId
        GROUP BY backup_apps.packageName, backup_apps.userId
        ORDER BY backup_apps.isInstalled DESC, backup_apps.label COLLATE NOCASE
        """
    )
    fun observeApps(): Flow<List<AppBackupOverview>>

    @Query("SELECT * FROM backup_apps WHERE packageName = :packageName AND userId = :userId LIMIT 1")
    fun observeApp(packageName: String, userId: Int): Flow<BackupAppEntity?>

    @Query("SELECT * FROM backup_apps WHERE packageName = :packageName AND userId = :userId LIMIT 1")
    suspend fun getApp(packageName: String, userId: Int): BackupAppEntity?

    @Query(
        """
        SELECT * FROM backup_revisions
        WHERE packageName = :packageName AND userId = :userId
        ORDER BY createdAt DESC
        """
    )
    fun observeRevisions(packageName: String, userId: Int): Flow<List<BackupRevisionEntity>>

}
