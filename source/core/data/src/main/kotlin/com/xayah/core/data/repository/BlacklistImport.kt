package com.xayah.core.data.repository

import androidx.room.withTransaction
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.CompressionType
import com.xayah.core.model.ConfigurationsBlacklist
import com.xayah.core.model.OpType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.MediaExtraInfo
import com.xayah.core.model.database.MediaIndexInfo
import com.xayah.core.model.database.MediaInfo
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil

suspend fun AppDatabase.importBlacklist(blacklist: ConfigurationsBlacklist) = withTransaction {
    val packages = packageDao()
    require(blacklist.apps.all { PathUtil.isValidPackageName(it.packageName) && it.userId >= 0 }) { "Invalid blacklist application" }
    require(blacklist.files.all {
        it.name.isNotBlank() && it.name != "." && it.name != ".." && '/' !in it.name && FileUtil.normalizeAbsolutePath(it.path) != null
    }) { "Invalid blacklist file" }
    packages.clearBlocked(OpType.BACKUP)
    for (item in blacklist.apps) {
        val existing = packages.query(item.packageName, OpType.BACKUP, item.userId)
        if (existing == null) {
            packages.upsert(PackageEntity(
                id = 0,
                indexInfo = PackageIndexInfo(OpType.BACKUP, item.packageName, item.userId, CompressionType.ZSTD, 0, "", ""),
                packageInfo = PackageInfo(item.packageName, "", 0, 0, 0, 0),
                extraInfo = PackageExtraInfo(-1, false, emptyList(), "", 0, blocked = true, activated = false, firstUpdated = false, enabled = true),
                dataStates = PackageDataStates(), storageStats = PackageStorageStats(), dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            ))
        } else {
            packages.setBlocked(listOf(existing.id), true)
        }
    }
    val media = mediaDao()
    media.clearBlocked(OpType.BACKUP)
    for (item in blacklist.files) {
        val existing = media.query(item.name, OpType.BACKUP)
        if (existing == null) {
            media.upsert(MediaEntity(
                id = 0, indexInfo = MediaIndexInfo(OpType.BACKUP, item.name, CompressionType.TAR, 0, "", ""),
                mediaInfo = MediaInfo(item.path, 0, 0),
                extraInfo = MediaExtraInfo(0, blocked = true, activated = false, existed = false),
            ))
        } else {
            media.setBlocked(listOf(existing.id), true)
        }
    }
}
