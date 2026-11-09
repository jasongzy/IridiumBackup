package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.common.util.toSpaceString
import com.xayah.core.data.R
import com.xayah.core.database.dao.DirectoryDao
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.ConstantUtil.DEFAULT_PATH_PARENT
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.model.StorageType
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PreparationUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directoryDao: DirectoryDao,
    private val rootService: RemoteRootService,
) {
    private val updateMutex = Mutex()
    fun visibleDirectories(storageType: StorageType) = directoryDao.visibleDirectories(storageType).distinctUntilChanged()

    private suspend fun resetDir() {
        directoryDao.queryId(DEFAULT_PATH_PARENT, ConstantUtil.DEFAULT_PATH_CHILD)?.let { select(ConstantUtil.DEFAULT_PATH, it) }
    }

    suspend fun deleteDir(entity: DirectoryEntity) = updateMutex.withLock {
        if (directoryDao.querySelectedByDirectoryType()?.id == entity.id) resetDir()
        directoryDao.delete(entity)
    }

    suspend fun addDir(path: String) = updateMutex.withLock {
        directoryDao.addCustomDirectory(PathUtil.getParentPath(path), PathUtil.getFileName(path))
    }

    suspend fun selectDir(entity: DirectoryEntity) = updateMutex.withLock {
        val previousId = directoryDao.querySelectedByDirectoryType()?.id
        select(entity.path, entity.id) && previousId != entity.id
    }

    private suspend fun select(path: String, id: Long): Boolean {
        if (directoryDao.select(id) == 0) return false
        context.saveBackupSavePath(path)
        return true
    }

    suspend fun update(): String? = withIOContext {
        val (external, detectedPath) = updateMutex.withLock {
            val internal = rootService.listFilePaths(ConstantUtil.STORAGE_EMULATED_PATH, listFiles = false)
                .filter { PathUtil.getFileName(it).toIntOrNull() != null }
            val external = PreparationUtil.externalStorage()
            val discovered = mapOf(
                StorageType.INTERNAL to internal + DEFAULT_PATH_PARENT,
                StorageType.EXTERNAL to external.keys.filter { it.startsWith("/mnt/media_rw/") },
            )
            val unavailableCustom = directoryDao.customDirectories().filterNot { directory ->
                val volume = removableVolume(directory.path)
                rootService.exists(directory.parent) && (volume == null || external.hasVolume(volume))
            }.map { it.id }
            val firstScan = directoryDao.count() == 0
            directoryDao.refreshVolumes(discovered, ConstantUtil.DEFAULT_PATH_CHILD, unavailableCustom)
            val existing = if (firstScan) directoryDao.queryActiveDirectories().firstOrNull { rootService.exists(it.path) } else null
            val selected = directoryDao.querySelectedByDirectoryType()
            if (existing != null) select(existing.path, existing.id)
            else if (selected == null) resetDir()
            external to existing?.path
        }

        directoryDao.queryActiveDirectories().forEach { entity ->
            updateStats(entity)
            external[entity.parent]?.takeIf { entity.storageType == StorageType.EXTERNAL }?.let { type ->
                val limited = type.lowercase() !in ConstantUtil.SupportedExternalStorageFormat
                val tags = if (limited) listOf(type, context.getString(R.string.limited_4gb)) else listOf(type)
                val error = if (limited) {
                    "${context.getString(R.string.outdated_fs_warning)}\n\n" +
                        "${context.getString(R.string.recommend)}: ${ConstantUtil.SupportedExternalStorageFormat.toSpaceString()}"
                } else ""
                directoryDao.updateFileSystem(entity.id, type, tags, error, true)
            }
        }
        detectedPath
    }

    private suspend fun updateStats(directory: DirectoryEntity) {
        val path = directory.path
        val statFs = rootService.readStatFs(path)
        val backupBytes = if (directory.selected) rootService.calculateSize("$path/.") else null
        directoryDao.updateStats(directory.id, backupBytes, statFs.availableBytes, statFs.totalBytes)
    }

    suspend fun updateSelected() {
        withIOContext {
            directoryDao.querySelectedByDirectoryType()?.takeIf { it.active }?.let { updateStats(it) }
        }
    }

    fun querySelectedByDirectoryTypeFlow() = directoryDao.querySelectedByDirectoryTypeFlow()
}

private fun removableVolume(path: String): String? {
    val prefix = when {
        path.startsWith("/mnt/media_rw/") -> "/mnt/media_rw/"
        path.startsWith("/storage/") -> "/storage/"
        else -> return null
    }
    val volume = path.removePrefix(prefix).substringBefore('/')
    return (prefix + volume).takeUnless { volume.isEmpty() || it == "/storage/emulated" || it == "/storage/self" }
}

private fun Map<String, String>.hasVolume(volume: String): Boolean =
    volume in this || (volume.startsWith("/storage/") && "/mnt/media_rw/${volume.substringAfterLast('/')}" in this)

internal suspend fun isBackupVolumeMounted(path: String): Boolean =
    removableVolume(path)?.let { PreparationUtil.externalStorage().hasVolume(it) } ?: true

suspend fun Context.requireMountedBackupVolume(path: String) {
    check(isBackupVolumeMounted(path)) { getString(R.string.backup_storage_unavailable, path) }
}

suspend fun RemoteRootService.requireBackupStorage(context: Context, path: String, allowNewDirectory: Boolean = false) {
    context.requireMountedBackupVolume(path)
    check(existsChecked(if (allowNewDirectory) PathUtil.getParentPath(path) else path)) {
        context.getString(R.string.backup_storage_unavailable, path)
    }
}
