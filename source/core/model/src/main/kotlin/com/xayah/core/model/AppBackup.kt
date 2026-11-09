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
    val titaniumSource: TitaniumSource? = null,
)

data class TitaniumSource(val apkMd5: String?, val hasData: Boolean)

data class BackupManifestFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

private val archiveSuffixes = CompressionType.entries.map { it.suffix }.toSet()
private val sha256Hex = Regex("[0-9a-f]{64}")

fun archiveContentMask(names: Collection<String>): Int = names.fold(0) { mask, name ->
    if (name.substringAfter('.', "") !in archiveSuffixes) return@fold mask
    mask or when (name.substringBefore('.')) {
        DataType.PACKAGE_APK.type -> 1
        DataType.PACKAGE_USER.type -> 2
        DataType.PACKAGE_USER_DE.type -> 4
        DataType.PACKAGE_DATA.type -> 8
        DataType.PACKAGE_OBB.type -> 16
        DataType.PACKAGE_MEDIA.type -> 32
        else -> 0
    }
}

fun BackupManifest.hasValidFileEntries(): Boolean {
    val entries: List<BackupManifestFile?> = files ?: return false
    val names = mutableSetOf<String>()
    for (file in entries) {
        val name = file?.name
        if (name.isNullOrEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' } ||
            !names.add(name) || file.sizeBytes < 0 || !sha256Hex.matches(file.sha256.orEmpty())
        ) return false
    }
    return ConfigsPackageRestoreName in names && contentMask != 0 && contentMask == archiveContentMask(names) &&
        names.size == Integer.bitCount(contentMask) + 1
}

fun BackupManifest.matchesRevision(revision: BackupRevisionEntity): Boolean =
    schemaVersion == BACKUP_MANIFEST_SCHEMA_VERSION && packageName == revision.packageName &&
        userId == revision.userId && createdAt == revision.createdAt &&
        versionName == revision.appVersionName && versionCode == revision.appVersionCode &&
        contentMask == revision.contentMask

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
    revisions: Collection<BackupRevisionEntity>,
    retainCount: Int,
): List<RetentionRevision> {
    require(retainCount >= 1)
    return revisions.sortedWith(compareByDescending<BackupRevisionEntity> { it.createdAt }.thenBy { it.id })
        .groupBy { AppKey(it.packageName, it.userId) }
        .values
        .flatMap { appRevisions ->
            if (appRevisions.size <= retainCount) emptyList()
            else appRevisions.mapIndexed { index, revision ->
                RetentionRevision(revision, index >= retainCount && !revision.isProtectedByNote())
            }
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
