package com.xayah.feature.main.details

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
import java.lang.reflect.Proxy
import java.util.zip.ZipFile
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppShareFileTest {
    private lateinit var directory: File
    private lateinit var root: RemoteRootService
    private var sources = emptyList<String>()
    private var failCopy = false
    private var onCopy: () -> Unit = {}
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
        val remote = Proxy.newProxyInstance(
            IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "getPackageSourceDir" -> sources
                "copyTo" -> {
                    File(args!![0] as String).copyTo(File(args[1] as String), overwrite = args[2] as Boolean)
                    onCopy()
                    !failCopy
                }
                "setAllPermissions" -> null
                "deleteRecursively" -> {
                    val target = File(args!![0] as String)
                    assertTrue(target.path.startsWith(File(directory, "shared_apk").path + "/"))
                    target.deleteRecursively()
                }
                else -> error("Unexpected root operation: ${method.name}")
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
    fun subsequentSharesPreserveEarlierFilesAndPackSplitApks() = runBlocking {
        val first = requireNotNull(prepareAppShareFile(directory, app, root))
        File(sources.first()).writeText("updated APK")
        val second = requireNotNull(prepareAppShareFile(directory, app, root))
        assertNotEquals(first.path, second.path)
        assertEquals("Example-1.0.apk", first.name)
        assertEquals("original APK", first.readText())
        assertEquals("updated APK", second.readText())

        sources += File(directory, "split_config.apk").apply { writeText("split APK") }.path
        val split = requireNotNull(prepareAppShareFile(directory, app, root))
        assertEquals("Example-1.0.apks", split.name)
        ZipFile(split).use { zip ->
            assertEquals(2, zip.size())
            assertEquals("updated APK", zip.getInputStream(zip.getEntry("base.apk")).bufferedReader().use { it.readText() })
            assertEquals("split APK", zip.getInputStream(zip.getEntry("split_config.apk")).bufferedReader().use { it.readText() })
        }
        assertEquals("original APK", first.readText())
        assertFalse(File(split.parentFile, "staging").exists())
    }

    @Test
    fun failedAndCancelledSharesCleanOnlyTheirOwnFiles() = runBlocking {
        val shared = requireNotNull(prepareAppShareFile(directory, app, root))
        val sessions = File(directory, "shared_apk")
        failCopy = true
        assertNull(prepareAppShareFile(directory, app, root))
        assertEquals(1, sessions.listFiles()!!.size)
        failCopy = false
        sources += File(directory, "split.apk").apply { writeText("split APK") }.path
        val job = launch {
            onCopy = { cancel() }
            prepareAppShareFile(directory, app, root)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, sessions.listFiles()!!.size)
        assertEquals("original APK", shared.readText())
        assertEquals("original APK", File(sources.first()).readText())
    }
}
