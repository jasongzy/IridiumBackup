package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.datastore.LabelFilterMode
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.MediaExtraInfo
import com.xayah.core.model.database.MediaIndexInfo
import com.xayah.core.model.database.MediaInfo
import com.xayah.core.model.database.asExternalModel
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.ConfigsMediaRestoreName
import com.xayah.core.util.DateUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.withLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class FilesRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val filesDao: MediaDao,
    private val mediaRepo: MediaRepository,
    private val pathUtil: PathUtil,
    private val rootService: RemoteRootService,
    private val cloudRepo: CloudRepository,
) {
    companion object {
        private const val TAG = "FilesRepo"
    }

    private fun log(block: () -> String) = LogUtil.log(TAG, block())

    fun getFile(id: Long) = filesDao.queryFileFlow(id)

    fun getFiles(
        opType: OpType,
        listData: Flow<ListData>,
        refs: Flow<List<LabelFileCrossRefEntity>>,
        cloudName: String,
        backupDir: String
    ): Flow<List<File>> = combine(
        listData,
        refs.map { items -> items.groupBy { it.path to it.preserveId }.mapValues { (_, refs) -> refs.mapTo(mutableSetOf()) { it.label } } },
        when (opType) {
            OpType.BACKUP -> filesDao.queryFilesFlow(opType = opType, existed = true, blocked = false)
            OpType.RESTORE -> filesDao.queryFilesFlow(opType = opType, cloud = cloudName, backupDir = backupDir)
        }
    ) { lData, labelsByFile, files ->
        val data = lData.castTo<ListData.Files>()
        val filters = data.labelFilters
        val included = filters.filterValues { it == LabelFilterMode.INCLUDE }.keys
        val excluded = filters.filterValues { it == LabelFilterMode.EXCLUDE }.keys
        files.asSequence()
            .filter(mediaRepo.getKeyPredicateNew(key = data.searchQuery))
            .filter { file ->
                val fileLabels = labelsByFile[file.path to file.preserveId].orEmpty()
                (included.isEmpty() || fileLabels.any(included::contains)) && fileLabels.none(excluded::contains)
            }
            .sortedWith(mediaRepo.getSortComparatorNew(sortIndex = data.sortIndex, sortType = data.sortType))
            .sortedByDescending { p -> p.extraInfo.activated }.toList()
            .map(MediaEntity::asExternalModel)
    }.flowOn(defaultDispatcher)

    fun countSelectedFiles(opType: OpType) = filesDao.countActivatedFilesFlow(opType = opType, existed = true, blocked = false)

    suspend fun selectFile(id: Long, selected: Boolean) {
        filesDao.activateById(id, selected)
    }

    suspend fun selectAll(ids: List<Long>) {
        filesDao.activateByIds(ids, true)
    }

    suspend fun unselectAll(ids: List<Long>) {
        filesDao.activateByIds(ids, false)
    }

    suspend fun reverseAll(ids: List<Long>) {
        filesDao.reverseActivatedByIds(ids)
    }

    suspend fun deleteSelected(ids: List<Long>) =
        ids.count { id -> filesDao.queryById(id)?.let { mediaRepo.delete(it) } == true }

    suspend fun initialize() {
        // Add default medium for first time
        if (filesDao.count() == 0L) {
            ConstantUtil.DefaultMediaList.forEach { (name, path) ->
                filesDao.upsert(
                    MediaEntity(
                        id = 0,
                        indexInfo = MediaIndexInfo(
                            opType = OpType.BACKUP,
                            name = name,
                            compressionType = CompressionType.TAR,
                            preserveId = 0,
                            cloud = "",
                            backupDir = ""
                        ),
                        mediaInfo = MediaInfo(
                            path = path,
                            dataBytes = 0,
                            displayBytes = 0,
                        ),
                        extraInfo = MediaExtraInfo(
                            lastBackupTime = 0,
                            blocked = false,
                            activated = true,
                            existed = true,
                        ),
                    )
                )
            }
        }

        val files = filesDao.query(opType = OpType.BACKUP, blocked = false)
        files.forEach { m ->
            val size = rootService.calculateSize(m.path)
            val existed = rootService.exists(m.path)
            filesDao.upsert(m.copy(mediaInfo = m.mediaInfo.copy(displayBytes = size), extraInfo = m.extraInfo.copy(existed = existed, activated = m.extraInfo.activated && existed)))
        }
    }

    suspend fun load(cloudName: String, backupDir: String, onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val configs = mutableListOf<MediaEntity>()
        fun collect(file: MediaEntity, path: String, root: String) {
            check(file.indexInfo.opType == OpType.RESTORE && file.hasValidArchiveName() &&
                path == "$root/${file.archivesRelativeDir}/$ConfigsMediaRestoreName") { "Invalid backup config: $path" }
            configs += file.copy(
                id = 0,
                indexInfo = file.indexInfo.copy(cloud = cloudName, backupDir = backupDir),
                extraInfo = file.extraInfo.copy(existed = true, activated = false),
            )
        }
        if (cloudName.isEmpty()) {
            check(rootService.existsChecked(backupDir)) { "Cannot read directory: $backupDir" }
            val root = "$backupDir/${PathUtil.getFilesRelativeDir()}"
            if (!rootService.existsChecked(root)) return
            val dirs = rootService.readDirectory(root, includeFiles = false).directories
            dirs.forEachIndexed { index, dir ->
                onLoad(index, dirs.size, PathUtil.getFileName(dir))
                val path = PathUtil.getMediaRestoreConfigDst(dir)
                val text = rootService.readTextIfPresent(path) ?: return@forEachIndexed
                val file = checkNotNull(rootService.gsonUtil.fromJson<MediaEntity?>(text, MediaEntity::class.java)) { "Invalid backup config: $path" }
                collect(file, path, root)
            }
        } else {
            cloudRepo.withClient(cloudName) { client, _ ->
                val root = pathUtil.getCloudRemoteFilesDir(backupDir)
                if (!client.exists(root)) return@withClient
                val files = client.walkFileTree(root).filter { it.pathList.lastOrNull() == ConfigsMediaRestoreName }
                files.forEachIndexed { index, entry ->
                    onLoad(index, files.size, entry.pathString)
                    collect(cloudRepo.readBackupConfig(client, entry.pathString, MediaEntity::class.java), entry.pathString, root)
                }
            }
        }
        filesDao.addRestoreFiles(configs)
    }

    private fun renameDuplicateFile(name: String): String {
        val nameList = name.split("_").toMutableList()
        val index = nameList.first().toIntOrNull()
        if (index == null) {
            nameList.add("0")
        } else {
            nameList[nameList.lastIndex] = (index + 1).toString()
        }
        return nameList.joinToString(separator = "_")
    }

    suspend fun addFiles(pathList: List<String>) {
        val files = mutableListOf<MediaEntity>()
        var failure = 0
        pathList.forEach { pathString ->
            if (pathString.isNotEmpty()) {
                var name = PathUtil.getFileName(pathString)
                filesDao.query(opType = OpType.BACKUP, preserveId = 0, cloud = "", backupDir = "").forEach {
                    if (it.name == name && it.path != pathString) name = renameDuplicateFile(name)
                }
                files.forEach {
                    if (it.name == name && it.path != pathString) name = renameDuplicateFile(name)
                }
                val exists = filesDao.query(opType = OpType.BACKUP, preserveId = 0, name = name, cloud = "", backupDir = "") != null
                if (exists) {
                    failure++
                    log { "$name:${pathString} has already existed." }
                } else files.add(
                    MediaEntity(
                        id = 0,
                        indexInfo = MediaIndexInfo(
                            opType = OpType.BACKUP,
                            name = name,
                            compressionType = CompressionType.TAR,
                            preserveId = 0,
                            cloud = "",
                            backupDir = ""
                        ),
                        mediaInfo = MediaInfo(
                            path = pathString,
                            dataBytes = 0,
                            displayBytes = 0,
                        ),
                        extraInfo = MediaExtraInfo(
                            lastBackupTime = 0,
                            activated = true,
                            blocked = false,
                            existed = true
                        ),
                    )
                )
            }
        }
        filesDao.upsert(files)
    }

    suspend fun calculateLocalFileSize(file: MediaEntity) {
        filesDao.updateDisplaySize(file.id, rootService.calculateSize(file.path))
    }

    private fun getArchiveSrc(dstDir: String, ct: CompressionType) = "${dstDir}/${DataType.MEDIA_MEDIA.type}.${ct.suffix}"

    suspend fun calculateLocalFileArchiveSize(file: MediaEntity) {
        if (!file.hasValidArchiveName()) return
        val dir = "${file.indexInfo.backupDir}/${PathUtil.getFilesRelativeDir()}/${file.archivesRelativeDir}"
        filesDao.updateDisplaySize(file.id, rootService.calculateSize(getArchiveSrc(dir, file.indexInfo.compressionType)))
    }

    suspend fun setBlocked(ids: Collection<Long>, blocked: Boolean) = filesDao.setBlocked(ids, blocked)

    suspend fun delete(id: Long) {
        filesDao.delete(id)
    }

    suspend fun delete(ids: List<Long>) {
        filesDao.delete(ids)
    }

    suspend fun protectFile(cloudName: String?, file: MediaEntity) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                protectCloudFile(this, file)
            }
        } else {
            protectLocalFile(file)
        }
    }

    private suspend fun protectLocalFile(file: MediaEntity) {
        val protectedFile = file.copy(indexInfo = file.indexInfo.copy(preserveId = DateUtil.getTimestamp()))
        val fileDir = "${file.indexInfo.backupDir}/${PathUtil.getFilesRelativeDir()}"
        val src = "${fileDir}/${file.archivesRelativeDir}"
        val dst = "${fileDir}/${protectedFile.archivesRelativeDir}"
        rootService.writeJson(data = protectedFile, dst = PathUtil.getMediaRestoreConfigDst(src))
        rootService.renameTo(src, dst)
        filesDao.update(protectedFile)
    }

    private suspend fun protectCloudFile(cloudName: String, file: MediaEntity) = runCatching {
        cloudRepo.withClient(cloudName) { client, _ ->
            val protectedFile = file.copy(indexInfo = file.indexInfo.copy(preserveId = DateUtil.getTimestamp()))
            val remote = file.indexInfo.backupDir
            val remoteFilesDir = pathUtil.getCloudRemoteFilesDir(remote)
            val src = "${remoteFilesDir}/${file.archivesRelativeDir}"
            val dst = "${remoteFilesDir}/${protectedFile.archivesRelativeDir}"
            val tmpDir = pathUtil.newCloudTmpDir()
            val tmpJsonPath = PathUtil.getMediaRestoreConfigDst(tmpDir)
            rootService.writeJson(data = protectedFile, dst = tmpJsonPath)
            cloudRepo.upload(client = client, src = tmpJsonPath, dstDir = src)
            rootService.deleteRecursively(tmpDir)
            client.renameTo(src, dst)
        }
    }.withLog()

    suspend fun deleteFile(file: MediaEntity): Boolean = mediaRepo.delete(file)
}
