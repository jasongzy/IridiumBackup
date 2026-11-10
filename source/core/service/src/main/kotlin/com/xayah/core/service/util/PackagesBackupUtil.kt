package com.xayah.core.service.util

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCompressionLevel
import com.xayah.core.datastore.readFollowSymlinks
import com.xayah.core.data.util.archiveExclusions
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.PackageDataStates.Companion.getSelected
import com.xayah.core.model.util.get
import com.xayah.core.model.util.set
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.getCompressPara
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.IconRelativeDir
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.filesDir
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

internal fun PackageInfo.apkSources(): Map<String, String> = buildMap {
    val info = requireNotNull(applicationInfo)
    put("base.apk", info.sourceDir)
    info.splitSourceDirs.orEmpty().forEachIndexed { index, path ->
        val split = splitNames?.getOrNull(index) ?: File(path).nameWithoutExtension
        put("split_${index + 1}_${Uri.encode(split)}.apk", path)
    }
}

class PackagesBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val packageRepository: PackageRepository,
    private val commonBackupUtil: CommonBackupUtil,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "PackagesBackupUtil"
    }

    private fun log(onMsg: () -> String) = LogUtil.log(TAG, onMsg())

    private fun PackageDataStats.setBytes(type: DataType, bytes: Long) {
        when (type) {
            DataType.PACKAGE_APK -> apkBytes = bytes
            DataType.PACKAGE_USER -> userBytes = bytes
            DataType.PACKAGE_USER_DE -> userDeBytes = bytes
            DataType.PACKAGE_DATA -> dataBytes = bytes
            DataType.PACKAGE_OBB -> obbBytes = bytes
            DataType.PACKAGE_MEDIA -> mediaBytes = bytes
            else -> Unit
        }
    }

    private suspend fun TaskDetailPackageEntity.updateInfo(
        dataType: DataType,
        state: OperationState? = null,
        bytes: Long? = null,
        log: String? = null,
        content: String? = null,
    ) = run {
        set(dataType, bytes = bytes, log = log, content = content, state = state)
        taskDao.upsert(this)
    }

    private val tarCt = CompressionType.TAR
    fun getIconsDst(dstDir: String) = "${dstDir}/$IconRelativeDir.${tarCt.suffix}"
    suspend fun backupIcons(dstDir: String): ShellResult = run {
        log { "Backing up icons..." }

        val dst = getIconsDst(dstDir = dstDir)
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        Tar.compress(
            h = "",
            srcDir = context.filesDir(),
            src = IconRelativeDir,
            dst = dst,
            extra = tarCt.getCompressPara(context.readCompressionLevel().first())
        ).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }
        commonBackupUtil.testArchive(src = dst, ct = tarCt).also { result ->
            isSuccess = isSuccess && result.isSuccess
            out.addAll(result.out)
            if (!result.isSuccess) rootService.removeEntry(dst)
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun backupApk(
        p: PackageEntity,
        sources: Map<String, String>,
        previousArchive: String?,
        t: TaskDetailPackageEntity,
        dstDir: String,
    ): ShellResult {
        val dataType = DataType.PACKAGE_APK
        if (!p.apkSelected) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
            return ShellResult(0, emptyList(), emptyList())
        }
        log { "Backing up apk..." }
        val ct = p.indexInfo.compressionType
        val dst = packageRepository.getArchiveDst(dstDir, dataType, ct)
        val size = sources.values.sumOf { rootService.calculateSize(it) }
        t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = size)
        val reused = previousArchive != null && commonBackupUtil.testArchive(previousArchive, ct).isSuccess &&
            (rootService.createHardLink(previousArchive, dst) || rootService.copyTo(previousArchive, dst, overwrite = true))
        val result = if (reused) {
            ShellResult(0, emptyList(), listOf(log { "Reused APK archive from the latest matching version." }))
        } else {
            val archive = Tar.compressApks(context.cacheDir, sources, dst, ct.getCompressPara(context.readCompressionLevel().first()))
            if (archive.isSuccess) {
                commonBackupUtil.testArchive(dst, ct).let { it.copy(out = archive.out + it.out) }
            } else archive
        }
        if (result.isSuccess) {
            p.dataStats.setBytes(dataType, size)
            p.displayStats.setBytes(dataType, rootService.calculateSize(dst))
        }
        t.updateInfo(dataType = dataType, state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = result.outString)
        return result
    }

    /**
     * Package data: USER, USER_DE, DATA, OBB, MEDIA
     */
    suspend fun backupData(p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, dstDir: String): ShellResult = run {
        log { "Backing up ${dataType.type}..." }

        val packageName = p.packageName
        val userId = p.userId
        val ct = p.indexInfo.compressionType
        val dst = packageRepository.getArchiveDst(dstDir = dstDir, dataType = dataType, ct = ct)
        var isSuccess: Boolean
        val out = mutableListOf<String>()
        val srcDir = packageRepository.getDataSrcDir(dataType, userId)

        if (!dataType.getSelected(p.dataStates)) {
            isSuccess = true
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            val src = packageRepository.getDataSrc(srcDir, packageName)
            val exists = try {
                rootService.existsChecked(src)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to inspect $src"
                t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = message)
                return@run ShellResult(-1, emptyList(), listOf(message))
            }
            if (!exists) {
                val required = dataType == DataType.PACKAGE_USER
                val message = if (required) "Not exist: $src" else "Not exist and skip: $src"
                t.updateInfo(dataType = dataType, state = if (required) OperationState.ERROR else OperationState.SKIP, log = message)
                return@run ShellResult(if (required) -1 else -2, emptyList(), listOf(message))
            }

            val exclusions = dataType.archiveExclusions(packageName)
            val sizeBytes = rootService.calculateSize(src, exclusions.children, exclusions.namePrefixes)
            t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
            Tar.compress(
                excludePaths = exclusions.paths,
                excludeNamePrefixes = exclusions.namePrefixes,
                h = if (context.readFollowSymlinks().first()) "-h" else "",
                srcDir = srcDir,
                src = packageName,
                dst = dst,
                extra = ct.getCompressPara(context.readCompressionLevel().first())
            ).also { result ->
                isSuccess = result.isSuccess
                out.addAll(result.out)
            }
            if (isSuccess) {
                val contents = Tar.hasContent(dst, packageName, ct.decompressPara)
                when (contents.getOrNull()) {
                    true -> {
                        p.dataStats.setBytes(dataType, sizeBytes)
                        p.displayStats.setBytes(dataType, rootService.calculateSize(dst))
                    }
                    false -> {
                        rootService.deleteRecursively(dst)
                        t.updateInfo(dataType = dataType, state = OperationState.SKIP)
                        return@run ShellResult(code = -2, input = listOf(), out = out)
                    }
                    null -> {
                        isSuccess = false
                        out.add(contents.exceptionOrNull().toString())
                    }
                }
            }

            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun backupPermissions(p: PackageEntity, packageInfo: PackageInfo) = run {
        log { "Backing up permissions..." }
        val permissions = rootService.getPermissions(packageInfo)
        p.extraInfo.permissions = permissions.orEmpty()
        log { if (permissions == null) "Could not read permissions for ${p.packageName}" else "Permissions: ${permissions.size}" }
    }

    suspend fun backupSsaid(p: PackageEntity) = run {
        log { "Backing up ssaid..." }

        val packageName = p.packageName
        val uid = p.extraInfo.uid
        val userId = p.userId

        p.extraInfo.ssaid = rootService.getPackageSsaidAsUser(packageName = packageName, uid = uid, userId = userId)
    }

    suspend fun upload(client: CloudClient, p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, srcDir: String, dstDir: String) = run {
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        t.updateInfo(dataType = dataType, state = OperationState.UPLOADING)

        val result = cloudRepository.upload(client, src, dstDir) { percent -> t.updateInfo(dataType = dataType, content = "$percent%") }
        t.updateInfo(dataType = dataType, state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = t.get(dataType).log + "\n${result.outString}")
    }
}
