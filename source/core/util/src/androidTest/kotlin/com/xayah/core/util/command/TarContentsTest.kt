package com.xayah.core.util.command

import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.util.FileUtil
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarContentsTest {
    @Test
    fun apkArchiveContainsOnlyTheRequestedBaseAndSplits() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val workspace = File.createTempFile("apk-sources-", ".tmp", cache)
        check(workspace.delete() && workspace.mkdir())
        try {
            val base = workspace.resolve("shared/base.apk").apply { check(parentFile!!.mkdir()); writeText("base") }
            base.resolveSibling("another.apk").writeText("unrelated")
            val split = workspace.resolve("another directory/base.apk").apply { check(parentFile!!.mkdir()); writeText("split") }
            val staging = workspace.resolve("cache").apply { check(mkdir()) }
            val sources = mapOf("base.apk" to base.path, "split_lang.apk" to split.path)
            val archive = workspace.resolve("result.tar")
            val result = Tar.compressApks(staging, sources, archive.path, "")
            assertTrue(result.outString, result.isSuccess)
            val contents = TarArchiveInputStream(archive.inputStream()).use { tar ->
                buildMap {
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        assertTrue(entry.isFile)
                        put(entry.name.removePrefix("./"), tar.readBytes().decodeToString())
                    }
                }
            }
            assertEquals(mapOf("base.apk" to "base", "split_lang.apk" to "split"), contents)
            assertFalse(Tar.compressApks(staging, sources, workspace.path, "").isSuccess)
            assertTrue(staging.list()!!.isEmpty())
        } finally {
            check(FileUtil.deleteRecursively(workspace.path))
        }
    }

    @Test
    fun emptyArchivesAndUnreadableArchivesHaveDifferentResults() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val workspace = File.createTempFile("archive-", ".tmp", cache)
        check(workspace.delete() && workspace.mkdir())
        try {
            val app = workspace.resolve("app").apply { check(mkdir()) }
            val archive = workspace.resolve("backup.tar")
            suspend fun pack() = check(BaseUtil.execute("tar -cf '${archive.path}' -C '${workspace.path}' app").isSuccess)
            check(BaseUtil.execute("set -o pipefail").isSuccess)
            pack()
            assertFalse(Tar.hasContent(archive.path, app.name, "").getOrThrow())
            app.resolve("settings").writeText("keep")
            pack()
            assertTrue(Tar.hasContent(archive.path, app.name, "").getOrThrow())
            archive.writeText("not a tar archive")
            assertTrue(Tar.hasContent(archive.path, app.name, "").isFailure)
        } finally {
            check(FileUtil.deleteRecursively(workspace.path))
        }
    }
}
