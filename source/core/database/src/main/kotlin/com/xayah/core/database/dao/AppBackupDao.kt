package com.xayah.core.database.dao

import android.content.pm.ApplicationInfo
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
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.toBackupApp
import kotlinx.coroutines.flow.Flow

private const val LATEST_BACKUP_TIME = """COALESCE((
    SELECT MAX(createdAt) FROM backup_revisions
    WHERE packageName = PackageEntity.indexInfo_packageName AND userId = PackageEntity.indexInfo_userId
), 0)"""

data class ConfigurationCounts(val blacklist: Int, val accounts: Int, val labels: Int, val appNotes: Int)

@Dao
interface AppBackupDao {
    @Query("SELECT DISTINCT userId FROM backup_apps ORDER BY userId")
    fun observeIndexedUserIds(): Flow<List<Int>>

    @Query("SELECT * FROM backup_apps WHERE userId = :userId AND (:packageName IS NULL OR packageName = :packageName)")
    suspend fun getIndexedApps(userId: Int, packageName: String?): List<BackupAppEntity>

    @Query("""
        SELECT indexInfo_packageName AS packageName, indexInfo_userId AS userId, packageInfo_label AS label,
            packageInfo_versionName AS versionName, packageInfo_versionCode AS versionCode,
            packageInfo_firstInstallTime AS firstInstallTime, packageInfo_lastUpdateTime AS lastUpdateTime,
            (packageInfo_flags & ${ApplicationInfo.FLAG_SYSTEM}) != 0 AS isSystem, 1 AS isInstalled, '' AS note
        FROM PackageEntity WHERE indexInfo_opType = :opType AND indexInfo_userId = :userId
            AND (:packageName IS NULL OR indexInfo_packageName = :packageName)
    """)
    suspend fun getScannedApps(userId: Int, packageName: String?, opType: OpType): List<BackupAppEntity>

    @Query("""
        SELECT substr(repositoryId, 2) FROM backup_revisions
        WHERE packageName = :packageName AND (:userId IS NULL OR userId = :userId) AND repositoryId LIKE ':/%'
        GROUP BY repositoryId ORDER BY MAX(createdAt) DESC, repositoryId
    """)
    suspend fun localBackupDirs(packageName: String, userId: Int?): List<String>

    @Query("SELECT DISTINCT packageName, userId FROM backup_revisions")
    fun observeBackedUpApps(): Flow<List<AppKey>>

    @Transaction
    suspend fun syncInstalledApps(userId: Int, packageName: String? = null): List<AppKey> {
        val indexed = getIndexedApps(userId, packageName).associateByTo(mutableMapOf(), BackupAppEntity::packageName)
        val changes = getScannedApps(userId, packageName, OpType.BACKUP).mapNotNull { app ->
            val old = indexed.remove(app.packageName)
            app.copy(note = old?.note.orEmpty()).takeUnless { it == old }
        }
        val removed = indexed.values.filter { it.isInstalled }
        upsertApps(changes + removed.map { it.copy(isInstalled = false, firstInstallTime = 0, lastUpdateTime = 0) })
        deleteUninstalledAppsWithoutRevisions(userId, packageName)
        syncLastBackupTimes(OpType.BACKUP, userId, packageName)
        return removed.map { AppKey(it.packageName, it.userId) }
    }

    @Upsert
    suspend fun upsertApps(apps: List<BackupAppEntity>)

    @Query("UPDATE backup_apps SET note = :note WHERE packageName = :packageName AND userId = :userId")
    suspend fun updateAppNote(packageName: String, userId: Int, note: String)

    @Transaction
    suspend fun saveAppNotes(notes: List<AppNoteItem>) {
        notes.forEach { item ->
            val note = item.note.trim()
            if (note.isNotEmpty()) {
                insertMissingApp(AppKey(item.packageName, item.userId).toBackupApp().copy(note = note))
            }
            updateAppNote(item.packageName, item.userId, note)
        }
        notes.filter { it.note.isBlank() }.map { it.userId }.distinct().forEach { deleteUninstalledAppsWithoutRevisions(it) }
    }

    @Query("SELECT * FROM backup_apps WHERE note != ''")
    suspend fun getAppsWithNotes(): List<BackupAppEntity>

    @Query("""
        SELECT (SELECT COUNT(*) FROM PackageEntity WHERE indexInfo_opType = 'BACKUP' AND extraInfo_blocked = 1) +
               (SELECT COUNT(*) FROM MediaEntity WHERE indexInfo_opType = 'BACKUP' AND extraInfo_blocked = 1) AS blacklist,
               (SELECT COUNT(*) FROM CloudEntity) AS accounts,
               (SELECT COUNT(*) FROM LabelEntity) + (SELECT COUNT(*) FROM LabelFileCrossRefEntity) +
               (SELECT COUNT(*) FROM (SELECT DISTINCT label, packageName, userId FROM LabelAppCrossRefEntity)) AS labels,
               (SELECT COUNT(*) FROM backup_apps WHERE note != '') AS appNotes
    """)
    fun observeConfigurationCounts(): Flow<ConfigurationCounts>

    @Query(
        """
        DELETE FROM backup_apps
        WHERE (:userId IS NULL OR userId = :userId) AND (:packageName IS NULL OR packageName = :packageName)
            AND isInstalled = 0 AND note = ''
            AND NOT EXISTS (
                SELECT 1 FROM backup_revisions
                WHERE backup_revisions.packageName = backup_apps.packageName
                    AND backup_revisions.userId = backup_apps.userId
            )
            AND NOT EXISTS (
                SELECT 1 FROM LabelAppCrossRefEntity
                WHERE LabelAppCrossRefEntity.packageName = backup_apps.packageName
                    AND LabelAppCrossRefEntity.userId = backup_apps.userId
            )
        """
    )
    suspend fun deleteUninstalledAppsWithoutRevisions(userId: Int? = null, packageName: String? = null)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRevision(revision: BackupRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingApp(app: BackupAppEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingApps(apps: List<BackupAppEntity>)

    @Transaction
    suspend fun recordRevision(app: BackupAppEntity, revision: BackupRevisionEntity) {
        insertMissingApp(app)
        insertRevision(revision)
        syncLastBackupTimes(OpType.BACKUP, app.userId, app.packageName)
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
        insertMissingApps(apps)
        upsertRevisions(revisions)
        syncLastBackupTimes(OpType.BACKUP)
    }

    @Query("SELECT * FROM backup_revisions WHERE packageName = :packageName AND userId = :userId AND repositoryId = :repositoryId AND (contentMask & 1) != 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestApkRevision(packageName: String, userId: Int, repositoryId: String): BackupRevisionEntity?

    @Query("""
        SELECT a.packageName, a.userId, p.packageInfo_label AS label, p.packageInfo_versionName AS versionName,
            p.packageInfo_versionCode AS versionCode, 0 AS firstInstallTime, 0 AS lastUpdateTime,
            (p.packageInfo_flags & ${ApplicationInfo.FLAG_SYSTEM}) != 0 AS isSystem, a.isInstalled, a.note
        FROM backup_apps a
        JOIN backup_revisions r ON r.revisionId = (
            SELECT revisionId FROM backup_revisions WHERE packageName = a.packageName AND userId = a.userId
            ORDER BY createdAt DESC, repositoryId, artifactId LIMIT 1
        )
        JOIN PackageEntity p ON p.indexInfo_packageName = a.packageName AND p.indexInfo_userId = a.userId
            AND p.indexInfo_preserveId = r.createdAt AND p.indexInfo_opType = :opType
            AND (p.indexInfo_cloud || ':' || p.indexInfo_backupDir) = r.repositoryId
        WHERE a.isInstalled = 0 AND a.userId = :userId AND a.packageName IN (:names)
    """)
    suspend fun latestArchivedApps(userId: Int, names: List<String>, opType: OpType): List<BackupAppEntity>

    @Query("""
        SELECT r.*, p.* FROM backup_apps a
        JOIN backup_revisions r ON r.revisionId = (
            SELECT revisionId FROM backup_revisions WHERE packageName = a.packageName AND userId = a.userId
                AND repositoryId = ':' || :backupDir ORDER BY createdAt DESC, revisionId LIMIT 1
        )
        JOIN PackageEntity p ON p.indexInfo_packageName = a.packageName AND p.indexInfo_userId = a.userId
            AND p.indexInfo_preserveId = r.createdAt AND p.indexInfo_opType = :opType
            AND p.indexInfo_cloud = '' AND p.indexInfo_backupDir = :backupDir
        WHERE a.userId = :userId AND a.packageName IN (:names)
    """)
    suspend fun latestLocalRestorePackages(backupDir: String, userId: Int, names: List<String>, opType: OpType): Map<BackupRevisionEntity, PackageEntity>

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
    fun observeRevisionCount(repositoryId: String): Flow<Int>

    @Query("SELECT * FROM backup_revisions WHERE repositoryId = :repositoryId AND artifactId IN (:artifactIds)")
    suspend fun getRevisions(repositoryId: String, artifactIds: List<String>): List<BackupRevisionEntity>

    @Query("SELECT DISTINCT packageName, userId FROM backup_revisions WHERE repositoryId = :repositoryId AND engine = :engine")
    fun observeBackedUpApps(repositoryId: String, engine: BackupEngine): Flow<List<AppKey>>

    @Query("DELETE FROM backup_revisions WHERE repositoryId = :repositoryId")
    suspend fun deleteRevisions(repositoryId: String)

    @Query("DELETE FROM backup_revisions WHERE revisionId = :revisionId")
    suspend fun deleteRevision(revisionId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM backup_revisions WHERE revisionId = :revisionId)")
    suspend fun hasRevision(revisionId: String): Boolean

    @Transaction
    suspend fun deleteRevisionAndSync(revision: BackupRevisionEntity) {
        deleteRevision(revision.id)
        syncLastBackupTimes(OpType.BACKUP, revision.userId, revision.packageName)
    }

    @Query("""
        UPDATE PackageEntity SET extraInfo_lastBackupTime = $LATEST_BACKUP_TIME
        WHERE indexInfo_opType = :opType AND (:userId IS NULL OR indexInfo_userId = :userId)
            AND (:packageName IS NULL OR indexInfo_packageName = :packageName)
            AND extraInfo_lastBackupTime != $LATEST_BACKUP_TIME
    """)
    suspend fun syncLastBackupTimes(opType: OpType, userId: Int? = null, packageName: String? = null)

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
