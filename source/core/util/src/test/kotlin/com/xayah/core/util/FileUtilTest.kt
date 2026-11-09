package com.xayah.core.util

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUtilTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun normalizeAbsolutePathRejectsUnsafePaths() {
        assertEquals("/storage/emulated/0/file", FileUtil.normalizeAbsolutePath("/storage/emulated/0/a/../file"))
        assertEquals("/storage/emulated/0", FileUtil.normalizeAbsolutePath("/storage//emulated/0/."))
        assertEquals(null, FileUtil.normalizeAbsolutePath("relative/path"))
        assertEquals(null, FileUtil.normalizeAbsolutePath("/../../storage"))
        assertEquals(null, FileUtil.normalizeAbsolutePath("/storage\\emulated\\0"))
    }

    @Test
    fun isDescendantRequiresARealChildPath() {
        assertFalse(FileUtil.isDescendant("/", "/"))
        assertTrue(FileUtil.isDescendant("/", "/storage"))
        assertTrue(FileUtil.isDescendant("/storage/emulated/0", "/storage/emulated/0/IridiumBackup"))
        assertFalse(FileUtil.isDescendant("/storage/emulated/0", "/storage/emulated/01"))
        assertFalse(FileUtil.isDescendant("/storage/emulated/0", "/storage/emulated/0"))
        assertFalse(FileUtil.isDescendant("/storage/emulated/0", "/storage/emulated/0/../10"))
    }

    @Test
    fun deleteRecursivelyDoesNotFollowSymbolicLinks() {
        val external = temporaryFolder.newFolder("external").toPath()
        val externalFile = Files.write(external.resolve("keep.txt"), "keep".toByteArray())
        val workspace = temporaryFolder.newFolder("workspace").toPath()
        Files.write(workspace.resolve("delete.txt"), "delete".toByteArray())
        try {
            Files.createSymbolicLink(workspace.resolve("shared"), external)
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertTrue(FileUtil.deleteRecursivelyApi26(workspace))
        assertFalse(Files.exists(workspace))
        assertTrue(Files.exists(externalFile))
    }

    @Test
    fun deleteRecursivelyRejectsSymbolicLinkParents() {
        val external = temporaryFolder.newFolder("external-parent").toPath()
        val nested = Files.createDirectory(external.resolve("nested"))
        val externalFile = Files.write(nested.resolve("keep.txt"), "keep".toByteArray())
        val workspace = temporaryFolder.newFolder("workspace-parent").toPath()
        try {
            Files.createSymbolicLink(workspace.resolve("shared"), external)
        } catch (error: Exception) {
            assumeNoException(error)
        }

        assertFalse(FileUtil.deleteRecursivelyApi26(workspace.resolve("shared/nested")))
        assertTrue(Files.exists(externalFile))
    }

    @Test
    fun protectsSharedDirectoriesForEachAndroidUser() {
        assertTrue(FileUtil.isProtectedDeletionPath("/"))
        assertTrue(FileUtil.isProtectedDeletionPath("/storage/emulated/0"))
        assertTrue(FileUtil.isProtectedDeletionPath("/data/media/0/Android"))
        assertTrue(FileUtil.isProtectedDeletionPath("/storage/emulated/10/DCIM"))
        assertTrue(FileUtil.isProtectedDeletionPath("/data/media/10/Pictures"))
        assertFalse(FileUtil.isProtectedDeletionPath("/storage/emulated/10/Pictures/album"))
        assertFalse(FileUtil.isProtectedDeletionPath("/storage/emulated/10/IridiumBackup"))
    }
}
