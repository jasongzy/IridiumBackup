package com.xayah.core.rootservice.impl

import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.util.archive.TarHeaderInspector.Link
import com.xayah.core.util.archive.TarHeaderInspector.LinkType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveLinkStoreTest {
    @get:Rule
    val temp = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

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
