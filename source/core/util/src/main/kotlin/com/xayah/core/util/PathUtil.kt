package com.xayah.core.util

import android.annotation.SuppressLint
import android.content.Context
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.model.ConfigsPackageRestoreName
import com.xayah.core.util.command.SELinux
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject

const val LogRelativeDir = "log"
const val IconRelativeDir = "icon"
const val BinRelativeDir = "bin"
const val TmpRelativeDir = "tmp"
const val ApksRelativeDir = "apks"
const val AppsRelativeDir = "apps"
const val FilesRelativeDir = "files"
const val ConfigsRelativeDir = "configs"
const val BackupManifestName = "backup_manifest.json"
const val AppIconName = "icon.bin"
const val ConfigsMediaRestoreName = "media_restore_config.json"
const val ConfigsConfigurationsName = "configurations.json"
const val BinArchiveName = "bin.zip"

fun Context.filesDir(): String = filesDir.path
fun Context.logDir(): String = "${filesDir()}/$LogRelativeDir"
fun Context.binDir(): String = "${filesDir()}/$BinRelativeDir"
fun Context.binArchivePath(): String = "${filesDir()}/$BinArchiveName"
fun Context.iconDir(): String = "${filesDir()}/$IconRelativeDir"
fun Context.tmpApksDir(): String = "${filesDir()}/$TmpRelativeDir/$ApksRelativeDir"
fun Context.localBackupSaveDir(): String = runBlocking { readBackupSavePath().first() }

class PathUtil @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val packageNamePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*$")

        fun isValidPackageName(packageName: String?): Boolean =
            packageName != null && packageNamePattern.matches(packageName)

        fun getPackageArchiveRelativeDir(packageName: String?, userId: Int, preserveId: Long): String? {
            if (!isValidPackageName(packageName) || userId < 0 || preserveId < 0) return null
            return "$packageName/user_$userId${if (preserveId == 0L) "" else "@$preserveId"}"
        }

        /**
         * Returns the parent path, or empty string if this path does not have a parent.
         */
        fun getParentPath(path: String): String {
            val separator = path.trimEnd('/').lastIndexOf('/')
            return when {
                separator < 0 -> ""
                separator == 0 -> "/"
                else -> path.substring(0, separator)
            }
        }

        /**
         * Returns the name of the file or directory denoted by this path, or empty string if this path has zero elements.
         */
        fun getFileName(path: String): String {
            if (path.isEmpty()) return ""
            return path.substring(path.lastIndexOf('/') + 1)
        }

        // Paths for processing.
        @SuppressLint("SdCardPath")
        fun getPackageUserDir(userId: Int): String = "/data/user/${userId}"
        fun getPackageUserDeDir(userId: Int): String = "/data/user_de/${userId}"
        fun getDataMediaDir(): String = "/data/media"
        fun getPackageDataDir(userId: Int): String = "${getDataMediaDir()}/${userId}/Android/data"
        fun getPackageObbDir(userId: Int): String = "${getDataMediaDir()}/${userId}/Android/obb"
        fun getPackageMediaDir(userId: Int): String = "${getDataMediaDir()}/${userId}/Android/media"

        fun getPackageIconRelativePath(packageName: String): String = "${packageName}.png"
        fun getPackageAdaptiveIconRelativePath(packageName: String): String = "adaptive@${getPackageIconRelativePath(packageName)}"
        fun getConfigsRelativeDir(): String = ConfigsRelativeDir

        fun getAppsRelativeDir(): String = AppsRelativeDir
        fun getFilesRelativeDir(): String = FilesRelativeDir
        fun getAppIconPath(appsDir: String, packageName: String): String = "$appsDir/$packageName/$AppIconName"


        fun getPackageRestoreConfigDst(dstDir: String): String = "${dstDir}/$ConfigsPackageRestoreName"
        fun getBackupManifestDst(dstDir: String): String = "${dstDir}/$BackupManifestName"
        fun getMediaRestoreConfigDst(dstDir: String): String = "${dstDir}/$ConfigsMediaRestoreName"

        suspend fun prepareCloudWorkspace(context: Context, path: String) {
            val cache = context.cacheDir.path
            require(FileUtil.isDescendant(cache, path))
            val workspace = path.removePrefix("$cache/").substringBefore('/')
            require(workspace.startsWith("cloud-"))
            if (java.io.File(path).canRead()) return
            val root = "$cache/$workspace"
            val label = SELinux.getContext(cache)
            check(label.isSuccess && label.outString.isNotBlank() && SELinux.chcon(label.outString, root).isSuccess) { "Cannot label $root" }
            val uid = context.applicationInfo.uid.toUInt()
            check(SELinux.chown(uid, uid, root).isSuccess) { "Cannot access $root" }
        }

        fun getSsaidPath(userId: Int) = "/data/system/users/$userId/settings_ssaid.xml"

        fun getPackageIconPath(context: Context, packageName: String, adaptive: Boolean): String = "${context.iconDir()}/${if (adaptive) getPackageAdaptiveIconRelativePath(packageName) else getPackageIconRelativePath(packageName)}"
    }

    fun newCloudTmpDir(): String = "${context.cacheDir}/cloud-${UUID.randomUUID()}"
    fun getPackageIconPath(packageName: String, adaptive: Boolean): String = getPackageIconPath(context, packageName, adaptive)
    private fun getConfigsDir(parent: String): String = "${parent.trimEnd('/')}/${getConfigsRelativeDir()}"
    fun getLocalBackupConfigsDir(): String = getConfigsDir(parent = context.localBackupSaveDir())
    fun getCloudRemoteConfigsDir(remote: String): String = getConfigsDir(parent = remote)

    private fun getAppsDir(parent: String): String = "${parent.trimEnd('/')}/${getAppsRelativeDir()}"
    private fun getFilesDir(parent: String): String = "${parent.trimEnd('/')}/${getFilesRelativeDir()}"
    fun getLocalBackupAppsDir(backupDir: String = context.localBackupSaveDir()): String = getAppsDir(parent = backupDir)
    fun getLocalBackupAppIconPath(packageName: String): String = getAppIconPath(getLocalBackupAppsDir(), packageName)
    fun getCloudRemoteAppsDir(remote: String): String = getAppsDir(parent = remote)
    fun getLocalBackupFilesDir(): String = getFilesDir(parent = context.localBackupSaveDir())
    fun getCloudRemoteFilesDir(remote: String): String = getFilesDir(parent = remote)


    fun getTmpApkPath(packageName: String): String = "${context.tmpApksDir()}/$packageName"
}
