package com.xayah.core.util.command

import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.util.FileUtil
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TarContentsTest {
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
