package com.xayah.core.rootservice.impl

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.libnative.NativeLib
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveSizeTest {
    @get:Rule
    val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun countsIncludedFilesAndLiteralLinksWithoutWalkingExcludedTrees() {
        System.loadLibrary("nativelib")
        val app = temp.newFolder("pkg")
        for (path in listOf("cache/ignored", "files/Backup_old/data", "files/pkg/cache/kept")) {
            app.resolve(path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(23)) }
        }
        val large = 5L shl 30
        RandomAccessFile(app.resolve("data"), "rw").use { it.setLength(large) }
        val link = app.resolve("external")
        Os.symlink(temp.root.path, link.path)
        val expected = large + Os.lstat(link.path).st_size
        assertEquals(expected + 23, NativeLib.calculateSize(app.path, arrayOf("cache"), arrayOf("Backup_")))
        assertEquals(expected + 69, NativeLib.calculateSize(app.path, emptyArray(), emptyArray()))
    }
}
