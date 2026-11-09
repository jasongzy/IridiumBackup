package com.xayah.core.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.xayah.core.model.CompressionType
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun isModule(apkPath: String, packageName: String): Boolean? = withContext(Dispatchers.IO) {
        val info = rootService.getPackageArchiveInfo(apkPath)
        if (info != null && info.packageName != packageName) return@withContext null
        if (rootService.hasZipEntry(apkPath, MODULE_ENTRIES)) true else info?.let(::hasLegacyMetadata)
    }

    suspend fun isModuleInBackup(revisionDir: String, packageName: String, compression: CompressionType?): Boolean? =
        rootService.withBackupApks(context.cacheDir, revisionDir, compression) { apks -> isModule(apks, packageName) }

    internal suspend fun isModule(apks: List<String>, packageName: String): Boolean? {
        var inspected = false
        for (apk in apks) {
            when (isModule(apk, packageName)) {
                true -> return true
                false -> inspected = true
                null -> Unit
            }
        }
        return if (inspected) false else null
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
