package com.xayah.core.data.repository

import androidx.room.withTransaction
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.CompressionType
import com.xayah.core.model.BlacklistFileItem
import com.xayah.core.model.OpType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.MediaExtraInfo
import com.xayah.core.model.database.MediaIndexInfo
import com.xayah.core.model.database.MediaInfo
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.util.FileUtil

suspend fun AppDatabase.importBlacklist(installed: List<PackageEntity>, files: List<BlacklistFileItem>) = withTransaction {
    val packages = packageDao()
    require(files.all {
        it.name.isNotBlank() && it.name != "." && it.name != ".." && '/' !in it.name && FileUtil.normalizeAbsolutePath(it.path) != null
    }) { "Invalid blacklist file" }
    if (installed.isEmpty() && files.isEmpty()) return@withTransaction
    packages.clearBlocked(OpType.BACKUP)
    val (discovered, existing) = installed.partition { it.id == 0L }
    packages.setBlocked(existing.map { it.id }, true)
    packages.upsert(discovered.map { it.copy(extraInfo = it.extraInfo.copy(blocked = true)) })
    for (app in discovered) appBackupDao().syncInstalledApps(app.userId, app.packageName)
    val media = mediaDao()
    media.clearBlocked(OpType.BACKUP)
    for (item in files) {
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
