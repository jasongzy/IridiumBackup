package com.xayah.core.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import com.xayah.core.model.CompressionType
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.parcelables.ArchiveExtractionParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconRepairTest {
    @Test
    fun importedIconKeepsItsAspectRatioWithoutAllocatingTheFullImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AppIconRepository(context, RemoteRootService(context), PathUtil(context))
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val icon = checkNotNull(repository.normalize(png))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(icon, 0, icon.size, bounds)
        assertTrue(bounds.outWidth == 256 && bounds.outHeight == 128)
    }

    @Test
    fun repairsFromAReadableApkAndCleansExtractionFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "icon-repair-test").apply { mkdirs() }
        val revision = File(directory, "revision").apply { mkdirs() }
        File(revision, "apk.tar").createNewFile()
        val binder = Binder()
        val remote = Proxy.newProxyInstance(
            IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "exists" -> File(args!![0] as String).exists()
                "mkdirs" -> File(args!![0] as String).let { it.mkdirs() || it.isDirectory }
                "deleteRecursively" -> File(args!![0] as String).deleteRecursively()
                "listFilePaths" -> File(args!![0] as String).listFiles().orEmpty()
                    .sortedBy(File::getName).map(File::getPath)
                "copyTo", "replaceFile" -> {
                    File(args!![0] as String).copyTo(File(args[1] as String), overwrite = method.name == "replaceFile" || args[2] as Boolean)
                    true
                }
                "extractArchive" -> {
                    assertEquals(File(revision, "apk.tar").path, args!![0])
                    val extracted = File(args[1] as String)
                    File(extracted, "0-unreadable.apk").writeText("unreadable APK")
                    File(context.applicationInfo.sourceDir).copyTo(File(extracted, "base.apk"))
                    File(args[3] as String).mkdirs()
                    ArchiveExtractionParcelable(0, emptyList(), 0, 0)
                }
                else -> error("Unexpected root operation: ${method.name}")
            }
        }
        val root = RemoteRootService(context).apply {
            javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
        }
        try {
            val repository = AppIconRepository(context, root, PathUtil(context))
            assertFalse(repository.repairFromBackup(revision.path, directory.path, "com.example.foreign", CompressionType.TAR))
            assertTrue(repository.repairFromBackup(revision.path, directory.path, context.packageName, CompressionType.TAR))
            val bitmap = BitmapFactory.decodeFile(PathUtil.getAppIconPath(directory.path, context.packageName))
            assertNotNull(bitmap)
            bitmap.recycle()
            assertFalse(File(context.cacheDir, "app-icon-apk").exists())
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("inspect-apk-") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
