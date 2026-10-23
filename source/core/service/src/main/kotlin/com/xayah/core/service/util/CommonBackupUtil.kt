package com.xayah.core.service.util

import android.content.Context
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.datastore.readCompressionTest
import com.xayah.core.datastore.readConfigurationSettings
import com.xayah.core.model.BlacklistAppItem
import com.xayah.core.model.BlacklistFileItem
import com.xayah.core.model.CompressionType
import com.xayah.core.model.Configurations
import com.xayah.core.model.ConfigurationsBlacklist
import com.xayah.core.model.OpType
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.ConfigsConfigurationsName
import com.xayah.core.util.LogUtil
import com.xayah.core.util.command.Tar
import com.xayah.core.util.model.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class CommonBackupUtil @Inject constructor(
    @ApplicationContext val context: Context,
    private val appRepo: PackageRepository,
    private val fileRepo: MediaRepository,
    private val cloudRepo: CloudRepository,
    private val labelsRepo: LabelsRepo,
    private val appBackupRepository: AppBackupRepository,
    private val rootService: RemoteRootService,
) {
    companion object {
        private const val TAG = "CommonBackupUtil"
    }

    private fun log(onMsg: () -> String): String = run {
        val msg = onMsg()
        LogUtil.log { TAG to msg }
        msg
    }

    fun getItselfDst(dstDir: String) = "${dstDir}/IridiumBackup.zip"

    suspend fun backupItself(dstDir: String): ShellResult = run {
        log { "Backing up itself..." }

        val packageName = context.packageName
        val sourceDirList = rootService.getPackageSourceDir(packageName, android.os.Process.myUid() / 100000)
        val targetPath = getItselfDst(dstDir)
        val isSuccess = sourceDirList.isNotEmpty() && rootService.archiveApks(sourceDirList, targetPath)
        val message = when {
            sourceDirList.isEmpty() -> "Failed to get APK paths of $packageName."
            isSuccess -> "Saved APK archive to $targetPath."
            else -> "Failed to save APK archive to $targetPath."
        }
        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = listOf(log { message }))
    }

    suspend fun testArchive(src: String, ct: CompressionType) = run {
        var code: Int
        var input: List<String>
        val out = mutableListOf<String>()

        if (context.readCompressionTest().first()) {
            Tar.test(src = src, extra = ct.decompressPara)
                .also { result ->
                    code = result.code
                    input = result.input
                    if (result.isSuccess.not()) {
                        out.add(log { "$src is broken, trying to delete it." })
                        rootService.deleteRecursively(src)
                    } else {
                        out.add(log { "Everything seems fine." })
                    }
                }
        } else {
            code = 0
            input = listOf()
            out.add(log { "Skip testing." })
        }

        ShellResult(code = code, input = input, out = out)
    }

    fun getConfigsDst(dstDir: String) = "${dstDir}/$ConfigsConfigurationsName"

    suspend fun backupConfigs(
        dstDir: String,
        includeBlacklist: Boolean = true,
        includeCloud: Boolean = true,
        includeLabels: Boolean = true,
        includeAppNotes: Boolean = true,
        includeSettings: Boolean = true,
    ): ShellResult = run {
        log { "Backing up configs..." }

        val config = Configurations(
            blacklist = ConfigurationsBlacklist(apps = listOf(), files = listOf()),
            cloud = listOf(),
            labels = listOf(),
            labelColors = emptyMap(),
            labelAppRefs = listOf(),
            labelFileRefs = listOf(),
            appNotes = listOf(),
            settings = null,
        )


        if (includeBlacklist) {
            config.blacklist.apps = appRepo.queryPackages(opType = OpType.BACKUP, blocked = true).map {
                BlacklistAppItem(it.packageName, it.userId)
            }
            config.blacklist.files = fileRepo.query(opType = OpType.BACKUP, blocked = true).map {
                BlacklistFileItem(it.name, it.path)
            }
        }
        if (includeCloud) config.cloud = cloudRepo.query()
        if (includeLabels) {
            config.labels = labelsRepo.getLabels()
            config.labelColors = labelsRepo.getLabelColors()
            config.labelAppRefs = labelsRepo.getAppRefs()
            config.labelFileRefs = labelsRepo.getFileRefs()
        }
        if (includeAppNotes) config.appNotes = appBackupRepository.getAppNotes()
        if (includeSettings) config.settings = context.readConfigurationSettings()
        val dst = getConfigsDst(dstDir)
        var isSuccess: Boolean
        val out = mutableListOf<String>()
        rootService.writeJson(data = config, dst = dst).also { result ->
            isSuccess = result.isSuccess
            out.addAll(result.out)
        }

        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }
}
