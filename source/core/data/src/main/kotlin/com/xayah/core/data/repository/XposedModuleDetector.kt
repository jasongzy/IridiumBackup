package com.xayah.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.util.suffixOf
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

class XposedModuleDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) {
    suspend fun isInstalledModule(packageName: String, userId: Int): Boolean? {
        if (!rootService.queryInstalled(packageName, userId)) return null
        val packageInfo = rootService.getPackageInfoAsUser(packageName, PackageManager.GET_META_DATA, userId)
            ?: return null
        return isModule(packageInfo)
    }

    suspend fun isModule(packageInfo: PackageInfo): Boolean = withContext(Dispatchers.IO) {
        hasLegacyMetadata(packageInfo) || packageInfo.applicationInfo?.let { applicationInfo ->
            buildList {
                applicationInfo.sourceDir?.let(::add)
                applicationInfo.splitSourceDirs?.let(::addAll)
            }.any { rootService.hasZipEntry(it, MODULE_ENTRIES) }
        } == true
    }

    suspend fun isModule(apkPath: String): Boolean = inspectApk(apkPath) == true

    private suspend fun inspectApk(apkPath: String): Boolean? = withContext(Dispatchers.IO) {
        if (rootService.hasZipEntry(apkPath, MODULE_ENTRIES)) return@withContext true
        val packageInfo = rootService.getPackageArchiveInfo(apkPath) ?: return@withContext null
        hasLegacyMetadata(packageInfo)
    }

    suspend fun isModuleInBackup(revisionDir: String): Boolean? = withContext(Dispatchers.IO) {
        val apkArchive = rootService.listFilePaths(revisionDir, listDirs = false)
            .firstOrNull { PathUtil.getFileName(it).startsWith("${DataType.PACKAGE_APK.type}.") }
            ?: return@withContext null
        val suffix = PathUtil.getFileName(apkArchive).substringAfter("${DataType.PACKAGE_APK.type}.")
        val compression = CompressionType.suffixOf(suffix) ?: return@withContext null
        val extracted = File(context.cacheDir, "xposed-backup-${UUID.randomUUID()}")
        val workspace = File(context.cacheDir, "xposed-archive-${UUID.randomUUID()}")
        try {
            if (!rootService.mkdirs(extracted.path)) return@withContext null
            if (!rootService.extractArchive(
                    source = apkArchive,
                    destination = extracted.path,
                    compression = compression.decompressPara,
                    workspace = workspace.path,
                ).result.isSuccess
            ) return@withContext null
            val apks = rootService.listFilePaths(extracted.path, listDirs = false)
                .filter { it.endsWith(".apk", ignoreCase = true) }
            var inspected = false
            apks.forEach { apk ->
                inspectApk(apk)?.let { isModule ->
                    inspected = true
                    if (isModule) return@withContext true
                }
            }
            if (inspected) false else null
        } finally {
            withContext(NonCancellable) {
                rootService.deleteRecursively(extracted.path)
                rootService.deleteRecursively(workspace.path)
            }
        }
    }

    private fun hasLegacyMetadata(packageInfo: PackageInfo): Boolean =
        packageInfo.applicationInfo?.metaData?.containsKey(LEGACY_MIN_VERSION) == true

    private companion object {
        const val LEGACY_MIN_VERSION = "xposedminversion"
        val MODULE_ENTRIES = listOf(
            "assets/xposed_init",
            "META-INF/xposed/java_init.list",
            "META-INF/xposed/native_init.list",
        )
    }
}
