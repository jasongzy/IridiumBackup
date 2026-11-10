package com.xayah.core.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.xayah.core.model.CompressionType
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppIconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootService: RemoteRootService,
) {
    private val extractionMutex = Mutex()

    suspend fun saveInstalledIcon(info: ApplicationInfo, appsDir: String): String? {
        val drawable = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(info) }.getOrNull()
        } ?: return null
        val destination = PathUtil.getAppIconPath(appsDir, info.packageName)
        return destination.takeIf { writeDrawable(drawable, destination) }
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
            if (packageInfo.packageName != packageName) return false
            val applicationInfo = packageInfo.applicationInfo ?: return false
            applicationInfo.sourceDir = localApk.path
            applicationInfo.publicSourceDir = localApk.path
            writeDrawable(
                applicationInfo.loadIcon(context.packageManager),
                PathUtil.getAppIconPath(appsDir, packageName),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            FileUtil.deleteRecursively(cacheDir.path)
        }
    }

    suspend fun repairFromBackup(
        revisionDir: String,
        appsDir: String,
        packageName: String,
        compression: CompressionType?,
        overwrite: Boolean = false,
    ): Boolean = rootService.withBackupApks(context.cacheDir, revisionDir, compression) { apks ->
        saveIconFromApks(apks, appsDir, packageName, overwrite)
    } == true

    internal suspend fun saveIconFromApks(apks: List<String>, appsDir: String, packageName: String, overwrite: Boolean): Boolean =
        extractionMutex.withLock {
            if (!overwrite && rootService.exists(PathUtil.getAppIconPath(appsDir, packageName))) return@withLock false
            apks.any { saveApkIconUnlocked(it, appsDir, packageName) }
        }

    fun normalize(bytes: ByteArray): ByteArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val dimension = maxOf(options.outWidth, options.outHeight)
        if (dimension <= 0) return null
        options.inJustDecodeBounds = false
        options.inSampleSize = Integer.highestOneBit((dimension / ICON_SIZE).coerceAtLeast(1))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
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
