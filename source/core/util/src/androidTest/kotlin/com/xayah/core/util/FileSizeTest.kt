package com.xayah.core.util

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
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
            val expected = 18L + links.sumOf { Os.lstat(it.path).st_size }
            assertEquals(expected, FileUtil.calculateSizeApi24(root.path))
            links.forEach { link ->
                assertEquals(Os.lstat(link.path).st_size, FileUtil.calculateSizeApi24(link.path))
            }
            assertEquals(0L, FileUtil.calculateSizeApi24(File(workspace, "missing").path))
        } finally {
            check(FileUtil.deleteRecursively(workspace.path))
        }
    }
}
