package com.xayah.core.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.util.suffixOf
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppIconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
    private val pathUtil: PathUtil,
) {
    private val extractionMutex = Mutex()

    fun getLocalIconPath(packageName: String): String = pathUtil.getLocalBackupAppIconPath(packageName)

    suspend fun saveInstalledIcon(packageName: String, appsDir: String): String? {
        val drawable = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        } ?: return null
        val destination = PathUtil.getAppIconPath(appsDir, packageName)
        return destination.takeIf { writeDrawable(drawable, destination) }
    }

    suspend fun saveEncodedIcon(bytes: ByteArray, appsDir: String, packageName: String): Boolean {
        val normalized = normalize(bytes) ?: return false
        return rootService.writeBytes(normalized, PathUtil.getAppIconPath(appsDir, packageName))
    }

    suspend fun saveApkIcon(apkPath: String, appsDir: String, packageName: String): Boolean =
        extractionMutex.withLock { saveApkIconUnlocked(apkPath, appsDir, packageName) }

    private suspend fun saveApkIconUnlocked(apkPath: String, appsDir: String, packageName: String): Boolean {
        val cacheDir = File(context.cacheDir, "app-icon-apk").apply { mkdirs() }
        val localApk = File(cacheDir, "base.apk")
        return try {
            if (!rootService.copyTo(apkPath, localApk.path, overwrite = true)) return false
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    localApk.path,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(localApk.path, 0)
            } ?: return false
            val applicationInfo = packageInfo.applicationInfo ?: return false
            applicationInfo.sourceDir = localApk.path
            applicationInfo.publicSourceDir = localApk.path
            writeDrawable(
                applicationInfo.loadIcon(context.packageManager),
                PathUtil.getAppIconPath(appsDir, packageName),
            )
        } finally {
            FileUtil.deleteRecursively(cacheDir.path)
        }
    }

    suspend fun repairFromBackup(
        revisionDir: String,
        appsDir: String,
        packageName: String,
        overwrite: Boolean = false,
    ): Boolean = extractionMutex.withLock {
        val destination = PathUtil.getAppIconPath(appsDir, packageName)
        if (!overwrite && rootService.exists(destination)) return@withLock false
        val apkArchive = rootService.listFilePaths(revisionDir, listDirs = false)
            .firstOrNull { PathUtil.getFileName(it).startsWith("${DataType.PACKAGE_APK.type}.") }
            ?: return@withLock false
        val suffix = PathUtil.getFileName(apkArchive).substringAfter("${DataType.PACKAGE_APK.type}.")
        val compression = CompressionType.suffixOf(suffix) ?: return@withLock false
        val extracted = File(context.cacheDir, "app-icon-extract").path
        val workspace = File(context.cacheDir, "archive-${UUID.randomUUID()}").path
        try {
            rootService.deleteRecursively(extracted)
            if (!rootService.mkdirs(extracted)) return@withLock false
            if (!rootService.extractArchive(apkArchive, extracted, compression.decompressPara, workspace).result.isSuccess) {
                return@withLock false
            }
            rootService.listFilePaths(extracted, listDirs = false)
                .filter { it.endsWith(".apk", ignoreCase = true) }
                .any { saveApkIconUnlocked(it, appsDir, packageName) }
        } finally {
            withContext(NonCancellable) {
                rootService.deleteRecursively(extracted)
                rootService.deleteRecursively(workspace)
            }
        }
    }

    fun normalize(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun writeDrawable(drawable: Drawable, destination: String): Boolean = withContext(Dispatchers.IO) {
        val bitmap = drawable.toBitmap(width = ICON_SIZE, height = ICON_SIZE, config = Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) &&
                rootService.writeBytes(output.toByteArray(), destination)
        }
    }

    private companion object {
        const val ICON_SIZE = 256
    }
}
