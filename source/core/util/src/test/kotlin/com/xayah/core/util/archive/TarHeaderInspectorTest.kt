package com.xayah.core.util.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class TarHeaderInspectorTest {
    @Test
    fun preservesSupportedEntryNames() {
        val longName = "pkg/${"directory/".repeat(15)}file.txt"
        val archive = archive(
            Entry("./", TarConstants.LF_DIR),
            Entry("pkg/", TarConstants.LF_DIR),
            Entry("pkg/name with spaces.txt", data = "spaces".toByteArray()),
            Entry("pkg/name with spaces.txt", data = "new".toByteArray()),
            Entry("./pkg/name with spaces.txt", data = "newer".toByteArray()),
            Entry("pkg/line\nbreak.txt", data = "newline".toByteArray()),
            Entry(longName, data = "long".toByteArray()),
        )
        val approved = mutableListOf<String>()

        val result = TarHeaderInspector.inspect(ByteArrayInputStream(archive), requiredPrefix = "pkg/", cleanRestore = true, onApprovedEntry = approved::add)

        assertEquals(listOf("pkg/", "pkg/name with spaces.txt", "./pkg/name with spaces.txt", "pkg/line\nbreak.txt", longName), approved)
        assertEquals(emptyList<TarHeaderInspector.Link>(), result.links)
        assertEquals(0, result.skippedEntries)
    }

    @Test
    fun rejectsPathsOutsideRequiredRoot() {
        val traversal = archive(Entry("../outside", data = byteArrayOf(1)))
        val wrongRoot = archive(Entry("other/file", data = byteArrayOf(1)))

        assertThrows(IllegalStateException::class.java) {
            TarHeaderInspector.inspect(ByteArrayInputStream(traversal), requiredPrefix = "pkg") {}
        }
        assertThrows(IllegalStateException::class.java) {
            TarHeaderInspector.inspect(ByteArrayInputStream(wrongRoot), requiredPrefix = "pkg") {}
        }
    }

    @Test
    fun aListedPathCannotAlsoSelectALinkOrSpecialFile() {
        val file = Entry("pkg/value", data = byteArrayOf(1))
        for (type in listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_FIFO)) {
            val other = Entry("./pkg/value", type, target = "outside")
            for (order in listOf(arrayOf(file, other), arrayOf(other, file))) {
                assertThrows(IllegalStateException::class.java) {
                    TarHeaderInspector.inspect(ByteArrayInputStream(archive(*order)), "pkg") {}
                }
            }
        }
    }

    @Test
    fun rejectsCorruptHeadersAndCompressionTrailers() {
        val tar = archive(Entry("pkg/data", data = byteArrayOf(1)))
        val gzip = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(tar); it.write(ByteArray(128 * 1024)) }
        }.toByteArray()
        gzip[gzip.size - 8] = gzip[gzip.size - 8].inc()
        assertThrows(IOException::class.java) {
            TarHeaderInspector.inspect(GZIPInputStream(gzip.inputStream()), "pkg") {}
        }
        tar[0] = tar[0].inc()
        assertThrows(IllegalStateException::class.java) { TarHeaderInspector.inspect(tar.inputStream()) {} }
    }

    @Test
    fun cleanRestoreRequiresAnEndMarkerButAcceptsAnUnpaddedArchive() {
        val tar = archive(Entry("pkg/file", data = byteArrayOf(1)))
        TarHeaderInspector.inspect(tar.copyOf(1536).inputStream(), cleanRestore = true) {}
        assertThrows(IOException::class.java) {
            TarHeaderInspector.inspect(tar.copyOf(1024).inputStream(), cleanRestore = true) {}
        }
        TarHeaderInspector.inspect(tar.copyOf(1024).inputStream()) {}
    }

    @Test
    fun appliesRestoreExclusionsWithoutDroppingOtherEntries() {
        val archive = archive(
            Entry("pkg/cache/item", data = byteArrayOf(1)),
            Entry("pkg/files/Backup_old/value", data = byteArrayOf(1)),
            Entry("pkg/files/kept", data = byteArrayOf(1)),
        )
        val approved = mutableListOf<String>()

        TarHeaderInspector.inspect(
            input = ByteArrayInputStream(archive),
            requiredPrefix = "pkg",
            excludedPathPrefixes = setOf("pkg/cache"),
            excludedNamePrefixes = setOf("Backup_"),
            onApprovedEntry = approved::add,
        )

        assertEquals(listOf("pkg/files/kept"), approved)
    }

    @Test
    fun recordsLiteralLinksAndSkipsNestedLinkDestinations() {
        val archive = archive(
            Entry("pkg/external", TarConstants.LF_SYMLINK, target = "/outside/target"),
            Entry("pkg/external/nested", TarConstants.LF_SYMLINK, target = "relative"),
            Entry("pkg/hard", TarConstants.LF_LINK, target = "/existing/file"),
            Entry("pkg/fifo", TarConstants.LF_FIFO),
        )

        val result = TarHeaderInspector.inspect(ByteArrayInputStream(archive), requiredPrefix = "pkg/") {}

        assertEquals(
            listOf(
                TarHeaderInspector.Link(TarHeaderInspector.LinkType.SYMBOLIC, "pkg/external", "/outside/target"),
                TarHeaderInspector.Link(TarHeaderInspector.LinkType.HARD, "pkg/hard", "/existing/file"),
            ),
            result.links,
        )
        assertEquals(2, result.skippedEntries)
    }

    private fun archive(vararg entries: Entry): ByteArray = ByteArrayOutputStream().use { bytes ->
        TarArchiveOutputStream(bytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setAddPaxHeadersForNonAsciiNames(true)
            entries.forEach { item ->
                val entry = TarArchiveEntry(item.name, item.type).apply {
                    linkName = item.target
                    size = item.data.size.toLong()
                }
                tar.putArchiveEntry(entry)
                tar.write(item.data)
                tar.closeArchiveEntry()
            }
        }
        bytes.toByteArray()
    }

    private data class Entry(
        val name: String,
        val type: Byte = TarConstants.LF_NORMAL,
        val target: String = "",
        val data: ByteArray = byteArrayOf(),
    )
}
