package com.xayah.core.rootservice.impl

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.util.archive.TarHeaderInspector.Link
import com.xayah.core.util.archive.TarHeaderInspector.LinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveLinkStoreTest {
    @get:Rule
    val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test
    fun skipsFailedLinksWithoutRemovingTheOriginal() {
        val root = temp.newFolder("restore")
        val outside = temp.newFolder("outside")
        Os.symlink(outside.path, root.resolve("alias").path)
        val destination = root.resolve("name\\part/current").apply { parentFile!!.mkdir() }
        Os.symlink("old-target", destination.path)
        val link = Link(LinkType.HARD, destination.relativeTo(root).path, "missing")
        assertFalse(restoreArchiveLink(link, root.path))
        assertEquals("old-target", Os.readlink(destination.path))

        assertFalse(restoreArchiveLink(link.copy(target = "/system/build.prop"), root.path))
        assertEquals("old-target", Os.readlink(destination.path))
        val replacement = link.copy(type = LinkType.SYMBOLIC, target = "new-target")
        assertTrue(restoreArchiveLink(replacement, root.path))
        assertEquals("new-target", Os.readlink(destination.path))
        assertFalse(restoreArchiveLink(replacement.copy(path = "alias/new"), root.path))
        assertFalse(outside.resolve("new").exists())
    }

    @Test
    fun titaniumImportOnlyReplaysLinksInsidePackageDirectories() {
        val root = "data/data/com.example"
        val links = listOf("data/data", root, "${root}2/value", "$root/files/shared").map {
            Link(LinkType.SYMBOLIC, it, "/storage/emulated/0")
        }
        val journal = temp.newFile()
        ArchiveLinkStore.write(journal, links)
        assertEquals(listOf(links.last()), ArchiveLinkStore.read(journal, arrayOf(root)).toList())
        assertEquals(links, ArchiveLinkStore.read(journal, emptyArray()).toList())
    }
}
