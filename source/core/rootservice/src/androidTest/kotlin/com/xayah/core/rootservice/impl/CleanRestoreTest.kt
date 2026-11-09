package com.xayah.core.rootservice.impl

import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CleanRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val temp = TemporaryFolder(context.cacheDir)

    @Test
    fun invalidArchivesLeaveInstalledDataIntact() {
        val source = temp.newFile("user.tar")
        val userDir = temp.newFolder("user")
        val app = userDir.resolve("pkg").apply { mkdir() }
        val data = app.resolve("settings").apply { writeText("keep") }
        val valid = java.io.ByteArrayOutputStream().apply {
            TarArchiveOutputStream(this).use { tar ->
                tar.putArchiveEntry(TarArchiveEntry("pkg/value").apply { size = 1 })
                tar.write(42)
                tar.closeArchiveEntry()
            }
        }.toByteArray()
        val workspace = temp.newFolder("work")
        val wrongRoots = listOf(TarConstants.LF_NORMAL, TarConstants.LF_SYMLINK, TarConstants.LF_LINK).map { type ->
            java.io.ByteArrayOutputStream().apply {
                TarArchiveOutputStream(this).use { tar ->
                    tar.putArchiveEntry(TarArchiveEntry("pkg", type).apply { if (type != TarConstants.LF_NORMAL) linkName = "elsewhere" })
                    tar.closeArchiveEntry()
                }
            }.toByteArray()
        }
        val fileAsParent = java.io.ByteArrayOutputStream().apply {
            TarArchiveOutputStream(this).use { tar ->
                for (name in listOf("pkg/data", "pkg/data/child")) {
                    tar.putArchiveEntry(TarArchiveEntry(name))
                    tar.closeArchiveEntry()
                }
            }
        }.toByteArray()
        for (archive in listOf(ByteArray(1024), valid.copyOf(1024), valid.copyOf(1040), fileAsParent) + wrongRoots) {
            source.writeBytes(archive)
            val result = ArchiveExtractor(context).extract(
                source.path, userDir.path, "", workspace.path, app.path, "${app.name}/",
                emptyArray(), emptyArray(), true, true,
            )
            assertNotEquals(0, result.code)
            assertEquals("keep", data.readText())
        }
    }
}
