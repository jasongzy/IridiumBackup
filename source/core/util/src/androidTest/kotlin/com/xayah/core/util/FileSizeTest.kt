package com.xayah.core.util

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FileSizeTest {
    @Test
    fun countsLinksWithoutTraversingTheirTargets() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val workspace = File.createTempFile("file-size-", ".tmp", cache)
        check(workspace.delete() && workspace.mkdir())
        try {
            val external = File(workspace, "external").apply { mkdir() }
            val externalFile = File(external, "data").apply { writeBytes(ByteArray(4096)) }
            val root = File(workspace, "app").apply { mkdir() }
            File(root, "data").writeBytes(ByteArray(7))
            File(root, "nested").apply { mkdir() }.resolve("data").writeBytes(ByteArray(11))
            val links = listOf(
                "directory-link" to external.path,
                "file-link" to externalFile.path,
                "missing-link" to File(workspace, "missing").path,
                "cycle" to root.path,
            ).map { (name, target) ->
                File(root, name).also { Os.symlink(target, it.path) }
            }
            assertNull(FileUtil.statIfPresent(root.resolve("absent").path))
            assertNull(FileUtil.readText(root.resolve("absent").path))
            assertThrows(IllegalStateException::class.java) { FileUtil.readText(links[2].path) }
            assertEquals(4096L, FileUtil.statIfPresent(links[1].path)?.st_size)
            assertThrows(IllegalStateException::class.java) { FileUtil.statIfPresent(links[2].path) }
            assertThrows(IllegalStateException::class.java) { FileUtil.statIfPresent(externalFile.resolve("child").path) }
            val expected = 18L + links.sumOf { Os.lstat(it.path).st_size }
            assertEquals(expected, FileUtil.calculateSize(root.path))
            links.forEach { link ->
                assertEquals(Os.lstat(link.path).st_size, FileUtil.calculateSize(link.path))
            }
            assertEquals(0L, FileUtil.calculateSize(File(workspace, "missing").path))
        } finally {
            check(FileUtil.deleteRecursively(workspace.path))
        }
    }
}
