package com.xayah.core.rootservice.impl

import android.system.ErrnoException
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun sharesRawApksAndKeepsSplitsWithMatchingFilenames() {
        val base = temporary.newFile("base.apk").apply { writeText("base") }
        val split = temporary.newFolder("split").resolve("base.apk").apply { writeText("split") }
        val single = File(temporary.root, "Example.apk")
        exportApks(listOf(base), single)
        assertArrayEquals(base.readBytes(), single.readBytes())

        val archive = File(temporary.root, "Example.apks")
        exportApks(listOf(base, split), archive)
        ZipFile(archive).use { zip ->
            assertEquals("base", zip.getInputStream(zip.getEntry("base.apk")).bufferedReader().use { it.readText() })
            assertEquals("split", zip.getInputStream(zip.getEntry("split_1.apk")).bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun failedWritePreservesPreviousArchiveAndCleansTemporaryFile() {
        val source = temporary.newFile("base.apk").apply { writeText("apk") }
        val output = temporary.newFolder("output")
        val archive = File(output, "IridiumBackup.zip")
        exportApks(listOf(source), archive)
        val original = archive.readBytes()
        assertThrows(IOException::class.java) {
            exportApks(listOf(source, File(temporary.root, "missing.apk")), archive)
        }
        assertArrayEquals(original, archive.readBytes())
        assertEquals(listOf(archive.name), output.list()!!.toList())
    }

    @Test
    fun replacesSymbolicLinkWithoutChangingLinkedFile() {
        val source = temporary.newFile("base.apk").apply { writeText("apk") }
        val outside = temporary.newFile("outside").apply { writeText("keep") }
        val output = temporary.newFolder("output")
        val archive = File(output, "IridiumBackup.zip")
        Os.symlink(outside.path, archive.path)
        exportApks(listOf(source), archive)
        assertEquals("keep", outside.readText())
        ZipFile(archive).use { assertEquals(1, it.size()) }
        assertEquals(listOf(archive.name), output.list()!!.toList())
    }

    @Test
    fun failedRenamePreservesDestinationAndCleansTemporaryFile() {
        val source = temporary.newFile("base.apk").apply { writeText("apk") }
        val output = temporary.newFolder("output")
        val archive = File(output, "IridiumBackup.zip").apply { mkdir() }
        val existing = File(archive, "keep").apply { writeText("keep") }
        assertThrows(ErrnoException::class.java) { exportApks(listOf(source), archive) }
        assertEquals("keep", existing.readText())
        assertEquals(listOf(archive.name), output.list()!!.toList())
    }

    @Test
    fun replacesBaseAndSplitApksOnSharedStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = TemporaryFolder(requireNotNull(context.externalCacheDir))
        output.create()
        try {
            val source = temporary.newFile("base.apk")
            val split = temporary.newFile("split_config.apk").apply { writeText("split") }
            val archive = File(output.root, "IridiumBackup.zip")
            for (content in listOf("original", "replacement")) {
                source.writeText(content)
                exportApks(listOf(source, split), archive)
                ZipFile(archive).use { zip ->
                    assertEquals(2, zip.size())
                    assertEquals(content, zip.getInputStream(zip.getEntry("IridiumBackup.apk")).bufferedReader().use { it.readText() })
                    assertEquals("split", zip.getInputStream(zip.getEntry(split.name)).bufferedReader().use { it.readText() })
                }
                assertEquals(listOf(archive.name), output.root.list()!!.toList())
            }
        } finally {
            output.delete()
        }
    }
}
