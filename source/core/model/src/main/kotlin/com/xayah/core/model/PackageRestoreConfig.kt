package com.xayah.core.model

import android.content.pm.ApplicationInfo
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.model.database.PackageStorageStats
import kotlinx.serialization.Serializable

const val PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION = 1
const val ConfigsPackageRestoreName = "package_restore_config.json"

@Serializable
data class PackageRestoreConfig(
    val schemaVersion: Int = PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION,
    val packageName: String,
    val userId: Int,
    val createdAt: Long,
    val compressionType: CompressionType,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val hasKeystore: Boolean,
    val permissions: List<PackagePermission>,
    val ssaid: String,
    val dataStates: PackageDataStates,
    val dataStats: PackageDataStats,
    val displayStats: PackageDataStats,
)

private fun appSsaid(packageName: String, value: String) = if (packageName == "android") "" else value

fun PackageEntity.toRestoreConfig() = PackageRestoreConfig(
    packageName = packageName,
    userId = userId,
    createdAt = preserveId,
    compressionType = indexInfo.compressionType,
    label = packageInfo.label,
    versionName = packageInfo.versionName,
    versionCode = packageInfo.versionCode,
    isSystemApp = isSystemApp,
    hasKeystore = extraInfo.hasKeystore,
    permissions = extraInfo.permissions,
    ssaid = appSsaid(packageName, extraInfo.ssaid),
    dataStates = dataStates.copy(),
    dataStats = dataStats.copy(),
    displayStats = displayStats.copy(),
)

fun PackageRestoreConfig.toPackageEntity(cloud: String, backupDir: String) = PackageEntity(
    id = 0,
    indexInfo = PackageIndexInfo(
        opType = OpType.RESTORE,
        packageName = packageName,
        userId = userId,
        compressionType = compressionType,
        preserveId = createdAt,
        cloud = cloud,
        backupDir = backupDir,
    ),
    packageInfo = PackageInfo(
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        flags = if (isSystemApp) ApplicationInfo.FLAG_SYSTEM else 0,
        firstInstallTime = 0,
        lastUpdateTime = 0,
    ),
    extraInfo = PackageExtraInfo(
        uid = -1,
        hasKeystore = hasKeystore,
        permissions = permissions,
        ssaid = appSsaid(packageName, ssaid),
        lastBackupTime = createdAt,
        blocked = false,
        activated = false,
        firstUpdated = false,
        enabled = true,
    ),
    dataStates = dataStates.copy(),
    storageStats = PackageStorageStats(),
    dataStats = dataStats.copy(),
    displayStats = displayStats.copy(),
)
