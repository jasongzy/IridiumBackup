package com.xayah.core.util

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveEntryTest {
    @Test
    fun emptyDirectoryCleanupLeavesOtherEntriesAlone() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File.createTempFile("cleanup-", ".tmp", cache)
        check(root.delete() && root.mkdir())
        try {
            val revision = root.resolve("revision").apply { mkdir() }
            val link = revision.resolve("link")
            Os.symlink(root.resolve("missing").path, link.path)
            assertFalse(FileUtil.removeEntry(revision.path, directory = true))
            assertFalse(FileUtil.removeEntry(link.path, directory = true))
            assertTrue(FileUtil.removeEntry(link.path, directory = false))
            val alias = root.resolve("alias")
            Os.symlink(root.path, alias.path)
            assertFalse(FileUtil.removeEntry(alias.resolve("revision").path, directory = true))
            assertTrue(FileUtil.removeEntry(revision.path, directory = true))
        } finally {
            check(FileUtil.deleteRecursively(root.path))
        }
    }
}
