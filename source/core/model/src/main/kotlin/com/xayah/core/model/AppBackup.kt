package com.xayah.core.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

const val BACKUP_MANIFEST_SCHEMA_VERSION = 1

enum class BackupEngine {
    LEGACY,
    RUSTIC,
}

enum class BackupVerificationStatus {
    NOT_VERIFIED,
    VALID,
    DAMAGED,
}

data class BackupManifest(
    val schemaVersion: Int = BACKUP_MANIFEST_SCHEMA_VERSION,
    val packageName: String,
    val userId: Int,
    val createdAt: Long,
    val versionName: String,
    val versionCode: Long,
    val contentMask: Int,
    val note: String? = null,
    val files: List<BackupManifestFile>?,
)

data class BackupManifestFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Entity(
    tableName = "backup_apps",
    primaryKeys = ["packageName", "userId"],
    indices = [Index("label")],
)
data class BackupAppEntity(
    val packageName: String,
    val userId: Int,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val isSystem: Boolean,
    val isInstalled: Boolean,
    @ColumnInfo(defaultValue = "''")
    val note: String = "",
)

fun AppKey.toBackupApp() = BackupAppEntity(
    packageName = packageName,
    userId = userId,
    label = packageName,
    versionName = "",
    versionCode = 0,
    firstInstallTime = 0,
    lastUpdateTime = 0,
    isSystem = false,
    isInstalled = false,
)

@Entity(
    tableName = "backup_revisions",
    foreignKeys = [
        ForeignKey(
            entity = BackupAppEntity::class,
            parentColumns = ["packageName", "userId"],
            childColumns = ["packageName", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["packageName", "userId"]),
        Index(value = ["createdAt"]),
        Index(value = ["engine", "repositoryId", "artifactId"], unique = true),
    ],
)
data class BackupRevisionEntity(
    val packageName: String,
    val userId: Int,
    val createdAt: Long,
    val appVersionName: String,
    val appVersionCode: Long,
    val engine: BackupEngine,
    val repositoryId: String,
    val artifactId: String,
    val contentMask: Int,
    val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val note: String = "",
    @PrimaryKey
    @ColumnInfo(name = "revisionId")
    val id: String = UUID.randomUUID().toString(),
)

private val protectedWord = Regex("""(?<![a-z])protected(?![a-z])""", RegexOption.IGNORE_CASE)

fun BackupRevisionEntity.isProtectedByNote(): Boolean = protectedWord.containsMatchIn(note)

data class RetentionRevision(
    val revision: BackupRevisionEntity,
    val removable: Boolean,
)

fun findRetentionRevisions(
    revisions: List<BackupRevisionEntity>,
    retainCount: Int,
): List<RetentionRevision> {
    require(retainCount >= 1)
    return revisions.groupBy { AppKey(it.packageName, it.userId) }
        .values
        .flatMap { appRevisions ->
            val sorted = appRevisions.sortedByDescending { it.createdAt }
            val removableIds = sorted.drop(retainCount)
                .filterNot(BackupRevisionEntity::isProtectedByNote)
                .mapTo(mutableSetOf(), BackupRevisionEntity::id)
            if (sorted.size <= retainCount) emptyList()
            else sorted.map { RetentionRevision(it, it.id in removableIds) }
        }
}

data class AppBackupOverview(
    @Embedded val app: BackupAppEntity,
    val revisionCount: Int,
    val latestRevisionAt: Long?,
    val hasApkBackup: Boolean,
    val hasDataBackup: Boolean,
    val hasMatchingApkBackup: Boolean,
    val revisionNotes: String,
)
