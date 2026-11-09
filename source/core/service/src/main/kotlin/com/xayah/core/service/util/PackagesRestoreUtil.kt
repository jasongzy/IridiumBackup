package com.xayah.core.service.util

import com.xayah.core.util.archive.finishArchiveStep
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PackageManager
import com.xayah.core.common.util.toLineString
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.datastore.readCleanRestoring
import com.xayah.core.data.util.archiveExclusions
import com.xayah.core.model.DataType
import com.xayah.core.model.OperationState
import com.xayah.core.model.database.PackageDataStates.Companion.getSelected
import com.xayah.core.model.util.get
import com.xayah.core.model.util.set
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.command.Pm
import com.xayah.core.util.command.SELinux
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class PackagesRestoreUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val rootService: RemoteRootService,
    private val taskDao: TaskDao,
    private val packageRepository: PackageRepository,
    private val cloudRepository: CloudRepository,
) {
    companion object {
        private const val TAG = "PackagesRestoreUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
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

    suspend fun restoreApk(userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, srcDir: String): ShellResult = finishArchiveStep {
        log { "Restoring apk..." }

        val dataType = DataType.PACKAGE_APK
        val packageName = p.packageName
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        var isSuccess = true
        val out = mutableListOf<String>()

        if (!dataType.getSelected(p.dataStates)) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            // Return if the archive doesn't exist.
            if (rootService.exists(src)) {
                val sizeBytes = rootService.calculateSize(src)
                t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                // Decompress apk archive
                val workspace = "${context.cacheDir}/archive-${UUID.randomUUID()}"
                val tmpApkPath = "$workspace/apks"
                try {
                    check(rootService.mkdirsWithin(context.cacheDir.path, tmpApkPath)) { "Unable to create APK workspace" }
                    rootService.extractArchive(
                        source = src,
                        destination = tmpApkPath,
                        compression = ct.decompressPara,
                        workspace = workspace,
                    ).result.also { result ->
                        isSuccess = result.isSuccess
                        out.addAll(result.out)
                    }
                    // Install apks
                    if (isSuccess) {
                        val apks = rootService.listFilePaths(tmpApkPath, listDirs = false).filter { it.endsWith(".apk") }
                        if (apks.none { rootService.getPackageArchiveInfo(it)?.packageName == packageName }) {
                            isSuccess = false
                            out.add(log { "Archive does not contain an APK for $packageName" })
                        } else {
                            val result = Pm.install(userId, apks)
                            isSuccess = result.isSuccess
                            out.addAll(result.out)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    isSuccess = false
                    out.add(log { e.message ?: e.javaClass.simpleName })
                } finally {
                    withContext(NonCancellable) {
                        rootService.deleteRecursively(workspace)
                    }
                }

                // Check the installation again.
                rootService.queryInstalled(packageName = packageName, userId = userId).also {
                    if (it.not()) {
                        isSuccess = false
                        out.add(log { "Not installed: $packageName." })
                    }
                }
            } else {
                isSuccess = false
                out.add(log { "Not exist: $src" })
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    /**
     * Package data: USER, USER_DE, DATA, OBB, MEDIA
     */
    suspend fun restoreData(userId: Int, p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, srcDir: String): ShellResult = finishArchiveStep {
        log { "Restoring ${dataType.type}..." }

        val packageName = p.packageName
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)
        val dstDir = packageRepository.getDataSrcDir(dataType, userId)
        val dst = packageRepository.getDataSrc(dstDir, packageName)
        var isSuccess = true
        val out = mutableListOf<String>()

        if (!dataType.getSelected(p.dataStates)) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
            if (uid == -1) {
                isSuccess = false
                out.add(log { "Failed to get uid of $packageName." })
            } else {
                // Return if the archive doesn't exist.
                if (rootService.exists(src)) {
                    val sizeBytes = rootService.calculateSize(src)
                    t.updateInfo(dataType = dataType, state = OperationState.PROCESSING, bytes = sizeBytes)
                    val exclusions = dataType.archiveExclusions(packageName)

                    // Get the SELinux context of the path.
                    val pathContext: String
                    SELinux.getContext(path = dst).also { result ->
                        pathContext = if (result.isSuccess) result.outString else ""
                    }

                    log { "Original SELinux context: $pathContext." }

                    // Decompress the archive.
                    val linkDir = "${context.cacheDir}/restore-links-${UUID.randomUUID()}"
                    try {
                        val gid = when (dataType) {
                            DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA -> rootService.fileGroupId(dstDir)
                            else -> uid.toUInt()
                        }
                        val extraction = rootService.extractArchive(
                            source = src,
                            destination = dstDir,
                            compression = ct.decompressPara,
                            workspace = linkDir,
                            cleanDestination = dst.takeIf { context.readCleanRestoring().first() }.orEmpty(),
                            requiredPrefix = packageName,
                            excludedPathPrefixes = exclusions.paths,
                            excludedNamePrefixes = exclusions.namePrefixes,
                            ignoreModificationTime = true,
                        )
                        isSuccess = extraction.result.isSuccess
                        out.addAll(extraction.result.out)

                        if (isSuccess) {
                            SELinux.chown(uid = uid.toUInt(), gid = gid, path = dst).also { result ->
                                isSuccess = isSuccess && result.isSuccess
                                out.addAll(result.out)
                            }
                            if (pathContext.isNotEmpty()) {
                                SELinux.chcon(context = pathContext, path = dst).also { result ->
                                    isSuccess = isSuccess && result.isSuccess
                                    out.addAll(result.out)
                                }
                            } else {
                                val parentContext: String
                                SELinux.getContext(dstDir).also { result ->
                                    parentContext = if (result.isSuccess) result.outString.replace("system_data_file", "app_data_file") else ""
                                }
                                if (parentContext.isNotEmpty()) {
                                    SELinux.chcon(context = parentContext, path = dst).also { result ->
                                        isSuccess = isSuccess && result.isSuccess
                                        out.addAll(result.out)
                                    }
                                } else {
                                    isSuccess = false
                                    out.add(log { "Failed to restore context: $dst" })
                                }
                            }
                            if (isSuccess) {
                                val restoredLinks = rootService.restoreArchiveLinks(linkDir, dstDir)
                                val skippedLinks = extraction.skippedEntries + extraction.pendingLinks - restoredLinks
                                if (skippedLinks > 0) out.add(log { "Skipped $skippedLinks unsafe or conflicting archive entries." })
                            }
                        }
                    } finally {
                        withContext(NonCancellable) { rootService.deleteRecursively(linkDir) }
                    }

                } else {
                    isSuccess = false
                    out.add(log { "Not exist: $src" })
                }
            }
            t.updateInfo(dataType = dataType, state = if (isSuccess) OperationState.DONE else OperationState.ERROR, log = out.toLineString())
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }

    suspend fun restorePermissions(userId: Int, p: PackageEntity) {
        if (!p.permissionSelected || p.extraInfo.permissions.isEmpty()) return

        val packageName = p.packageName
        val info = rootService.getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
        val uid = info?.applicationInfo?.uid
        val user = rootService.getUserHandle(userId)
        if (info == null || uid == null || user == null) {
            log { "Unable to restore permissions for $packageName (user $userId)." }
            return
        }

        val requested = rootService.getPermissions(info).associateBy { it.name }
        for (saved in p.extraInfo.permissions) {
            val current = requested[saved.name] ?: continue
            if (current.isGranted != saved.isGranted) {
                if (saved.isGranted) rootService.grantRuntimePermission(packageName, saved.name, user)
                else rootService.revokeRuntimePermission(packageName, saved.name, user)
            }
            val mode = saved.mode
            if (mode != null && current.op != AppOpsManagerHidden.OP_NONE && current.op == saved.op) {
                rootService.setOpsMode(current.op, uid, packageName, mode)
            }
        }
    }

    suspend fun restoreSsaid(userId: Int, p: PackageEntity) = run {
        log { "Restoring ssaid..." }

        val packageName = p.packageName
        val uid = rootService.getPackageUid(packageName = packageName, userId = userId)
        val ssaid = p.extraInfo.ssaid

        if (p.ssaidSelected) {
            if (uid != -1) {
                if (ssaid.isNotEmpty()) {
                    log { "Ssaid: $ssaid" }
                    rootService.setPackageSsaidAsUser(packageName, uid, userId, ssaid)
                } else {
                    log { "Ssaid is empty, skip." }
                }
            } else {
                log { "Failed to get uid of $packageName." }
            }
        } else {
            log { "Skip." }
        }
    }

    suspend fun download(
        client: CloudClient,
        p: PackageEntity,
        t: TaskDetailPackageEntity,
        dataType: DataType,
        srcDir: String,
        onDownloaded: suspend (p: PackageEntity, t: TaskDetailPackageEntity, dataType: DataType, path: String) -> Unit
    ) = run {
        val ct = p.indexInfo.compressionType
        val src = packageRepository.getArchiveDst(dstDir = srcDir, dataType = dataType, ct = ct)

        if (!dataType.getSelected(p.dataStates)) {
            t.updateInfo(dataType = dataType, state = OperationState.SKIP)
        } else {
            t.updateInfo(dataType = dataType, state = OperationState.DOWNLOADING)

            if (client.exists(src)) {
                cloudRepository.download(client = client,
                    src = src,
                    onDownloading = { written, _ -> t.updateInfo(dataType = dataType, content = written.toDouble().formatSize()) },
                    onDownloaded = {
                        onDownloaded(p, t, dataType, PathUtil.getParentPath(it))
                    }
                ).apply {
                    t.updateInfo(
                        dataType = dataType,
                        log = (t.get(dataType).log + "\n${outString}").trim(),
                    )
                    if (isSuccess.not()) {
                        t.updateInfo(dataType = dataType, state = OperationState.ERROR)
                    }
                }
            } else {
                t.updateInfo(dataType = dataType, state = OperationState.ERROR, log = log { "Remote archive not found: $src" })
            }
        }
    }
}
