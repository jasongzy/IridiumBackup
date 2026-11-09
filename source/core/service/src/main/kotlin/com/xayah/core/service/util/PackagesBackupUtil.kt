package com.xayah.core.service.util

import android.content.Context
import android.content.pm.PackageManager
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
import javax.inject.Inject

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

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    private fun PackageEntity.setDataBytes(dataType: DataType, sizeBytes: Long) = when (dataType) {
        DataType.PACKAGE_APK -> dataStats.apkBytes = sizeBytes
        DataType.PACKAGE_USER -> dataStats.userBytes = sizeBytes
        DataType.PACKAGE_USER_DE -> dataStats.userDeBytes = sizeBytes
        DataType.PACKAGE_DATA -> dataStats.dataBytes = sizeBytes
        DataType.PACKAGE_OBB -> dataStats.obbBytes = sizeBytes
        DataType.PACKAGE_MEDIA -> dataStats.mediaBytes = sizeBytes
        else -> Unit
    }

    private fun PackageEntity.setDisplayBytes(dataType: DataType, sizeBytes: Long) = when (dataType) {
        DataType.PACKAGE_APK -> displayStats.apkBytes = sizeBytes
        DataType.PACKAGE_USER -> displayStats.userBytes = sizeBytes
        DataType.PACKAGE_USER_DE -> displayStats.userDeBytes = sizeBytes
        DataType.PACKAGE_DATA -> displayStats.dataBytes = sizeBytes
        DataType.PACKAGE_OBB -> displayStats.obbBytes = sizeBytes
        DataType.PACKAGE_MEDIA -> displayStats.mediaBytes = sizeBytes
        else -> Unit
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
            exclusionList = listOf(),
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

    private suspend fun getPackageSourceDir(packageName: String, userId: Int) = rootService.getPackageSourceDir(packageName, userId).let { list ->
        if (list.isNotEmpty()) PathUtil.getParentPath(list[0]) else ""
    }

    suspend fun backupApk(
        p: PackageEntity,
        previousArchive: String?,
        t: TaskDetailPackageEntity,
        dstDir: String,
    ): ShellResult = run {
        log { "Backing up apk..." }

        val dataType = DataType.PACKAGE_APK
        val packageName = p.packageName
        val userId = p.userId
        val ct = p.indexInfo.compressionType
        val dst = packageRepository.getArchiveDst(dstDir = dstDir, dataType = dataType, ct = ct)
        var isSuccess: Boolean
        val out = mutableListOf<String>()
        val srcDir = getPackageSourceDir(packageName = packageName, userId = userId)

        if (!dataType.getSelected(p.dataStates)) {
            isSuccess = true
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            if (srcDir.isNotEmpty()) {
                val sizeBytes = rootService.calculateSize(srcDir)
                t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                val previousArchiveValid = previousArchive != null && commonBackupUtil.testArchive(
                    src = previousArchive,
                    ct = ct,
                ).isSuccess
                val reusedArchive = if (previousArchiveValid) {
                    val archive = checkNotNull(previousArchive)
                    rootService.createHardLink(archive, dst) ||
                        rootService.copyTo(archive, dst, overwrite = true)
                } else {
                    false
                }
                if (reusedArchive) {
                    isSuccess = true
                    p.setDataBytes(dataType, sizeBytes)
                    p.setDisplayBytes(dataType, rootService.calculateSize(dst))
                    out.add(log { "Reused APK archive from the latest matching version." })
                } else {
                    Tar.compressInCur(cur = srcDir, src = "./*.apk", dst = dst, extra = ct.getCompressPara(context.readCompressionLevel().first()))
                        .also { result ->
                            isSuccess = result.isSuccess
                            out.addAll(result.out)
                        }
                    commonBackupUtil.testArchive(src = dst, ct = ct).also { result ->
                        isSuccess = isSuccess && result.isSuccess
                        out.addAll(result.out)
                        if (result.isSuccess) {
                            p.setDataBytes(dataType, sizeBytes)
                            p.setDisplayBytes(dataType, rootService.calculateSize(dst))
                        }
                    }
                }
            } else {
                isSuccess = false
                out.add(log { "Failed to get apk path of $packageName." })
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
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

            val sizeBytes = rootService.calculateSize(src)
            t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
            Tar.compress(
                exclusionList = dataType.archiveExclusions(packageName).tarPatterns,
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
                        p.setDataBytes(dataType, sizeBytes)
                        p.setDisplayBytes(dataType, rootService.calculateSize(dst))
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

    suspend fun backupPermissions(p: PackageEntity) = run {
        log { "Backing up permissions..." }

        val packageName = p.packageName
        val userId = p.userId

        val packageInfo = rootService.getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
        packageInfo?.apply {
            p.extraInfo.permissions = rootService.getPermissions(packageInfo = this)
        }
        val permissions = p.extraInfo.permissions
        log { "Permissions size: ${permissions.size}..." }
        permissions.forEach {
            log { "Permission name: ${it.name}, isGranted: ${it.isGranted}, op: ${it.op}, mode: ${it.mode}" }
        }
    }

    suspend fun backupSsaid(p: PackageEntity) = run {
        log { "Backing up ssaid..." }

        val packageName = p.packageName
        val uid = p.extraInfo.uid
        val userId = p.userId

        val ssaid = rootService.getPackageSsaidAsUser(packageName = packageName, uid = uid, userId = userId)
        log { "Ssaid: $ssaid" }
        p.extraInfo.ssaid = ssaid
    }

    suspend fun upload(client: CloudClient, p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, srcDir: String, dstDir: String) = run {
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        t.updateInfo(dataType = dataType, state = OperationState.UPLOADING)

        val result = cloudRepository.upload(client, src, dstDir) { percent -> t.updateInfo(dataType = dataType, content = "$percent%") }
        t.updateInfo(dataType = dataType, state = if (result.isSuccess) OperationState.DONE else OperationState.ERROR, log = t.get(dataType).log + "\n${result.outString}")
    }
}
