package com.xayah.feature.main.details

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.CompressionType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.service.RemoteRootService
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppShareFileTest {
    private lateinit var directory: File
    private lateinit var root: RemoteRootService
    private var sources = emptyList<String>()
    private var failExport = false
    private var onExport: () -> Unit = {}
    private val app = PackageRestoreConfig(
        packageName = "com.example.share", userId = 0, createdAt = 0, compressionType = CompressionType.ZSTD,
        label = "Example", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
        permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
        dataStats = PackageDataStats(), displayStats = PackageDataStats(),
    ).toPackageEntity("", "")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "share-file-test").apply { mkdirs() }
        sources = listOf(File(directory, "source.apk").apply { writeText("original APK") }.path)
        val binder = Binder()
        val remote = object : IRemoteRootService.Default() {
            override fun asBinder() = binder
            override fun getPackageInfoAsUser(packageName: String, flags: Int, userId: Int) = PackageInfo().apply {
                versionName = "2.0"
                applicationInfo = ApplicationInfo().apply {
                    sourceDir = sources.first()
                    splitSourceDirs = sources.drop(1).toTypedArray()
                }
            }
            override fun exportApks(paths: Array<out String>, targetPath: String): Boolean {
                assertEquals(sources, paths.toList())
                onExport()
                if (failExport) return false
                File(targetPath).writeText(File(paths.first()).readText())
                return true
            }
            override fun deleteRecursively(path: String): Boolean {
                assertTrue(path.startsWith(File(directory, "shared_apk").path + "/"))
                return File(path).deleteRecursively()
            }
        }
        root = RemoteRootService(context).apply {
            javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun sharesUseTheInstalledVersionAndKeepEarlierExports() = runBlocking {
        val first = requireNotNull(prepareAppShareFile(directory, app, root))
        File(sources.first()).writeText("updated APK")
        val second = requireNotNull(prepareAppShareFile(directory, app, root))
        assertNotEquals(first.path, second.path)
        assertEquals("Example-2.0.apk", first.name)
        assertEquals("original APK", first.readText())
        assertEquals("updated APK", second.readText())

        sources += File(directory, "split_config.apk").apply { writeText("split APK") }.path
        val split = requireNotNull(prepareAppShareFile(directory, app, root))
        assertEquals("Example-2.0.apks", split.name)
    }

    @Test
    fun failedAndCancelledSharesCleanOnlyTheirOwnFiles() = runBlocking {
        val shared = requireNotNull(prepareAppShareFile(directory, app, root))
        val sessions = File(directory, "shared_apk")
        failExport = true
        assertNull(prepareAppShareFile(directory, app, root))
        assertEquals(1, sessions.listFiles()!!.size)
        failExport = false
        sources += File(directory, "split.apk").apply { writeText("split APK") }.path
        val job = launch {
            onExport = { cancel() }
            prepareAppShareFile(directory, app, root)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, sessions.listFiles()!!.size)
        assertEquals("original APK", shared.readText())
        assertEquals("original APK", File(sources.first()).readText())
    }
}
