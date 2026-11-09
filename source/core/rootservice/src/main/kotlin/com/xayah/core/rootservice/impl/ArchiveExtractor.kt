package com.xayah.core.rootservice.impl

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import com.xayah.core.rootservice.parcelables.ArchiveExtractionParcelable
import com.xayah.core.util.FileUtil
import com.xayah.core.util.archive.TarHeaderInspector
import com.xayah.core.util.archive.TarHeaderInspector.Link
import com.xayah.core.util.archive.TarHeaderInspector.LinkType
import com.xayah.core.util.binDir
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal class ArchiveExtractor(private val context: Context) {
    fun extract(
        source: String,
        destination: String,
        compression: String,
        workspace: String,
        cleanDestination: String,
        requiredPrefix: String,
        excludedPathPrefixes: Array<String>,
        excludedNamePrefixes: Array<String>,
        preservePermissions: Boolean,
        ignoreModificationTime: Boolean,
    ): ArchiveExtractionParcelable = runCatching {
        val normalizedWorkspace = FileUtil.normalizeAbsolutePath(workspace)
            ?: error("Invalid archive workspace")
        check(FileUtil.isDescendant(context.cacheDir.path, normalizedWorkspace)) { "Invalid archive workspace" }
        check(File(normalizedWorkspace).isDirectory) { "Unable to access archive workspace" }

        ParcelFileDescriptor.open(File(source), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val stableSource = "/proc/${AndroidProcess.myPid()}/fd/${descriptor.fd}"
            val inspection = inspect(
                source = stableSource,
                compression = compression,
                workspace = normalizedWorkspace,
                requiredPrefix = requiredPrefix,
                excludedPathPrefixes = excludedPathPrefixes,
                excludedNamePrefixes = excludedNamePrefixes,
                cleanRestore = cleanDestination.isNotEmpty(),
            )
            if (cleanDestination.isNotEmpty()) {
                check(inspection.entriesFile.length() > 0 || inspection.pendingLinks > 0) { "Archive has no entries to restore" }
                val normalizedDestination = FileUtil.normalizeAbsolutePath(destination)
                    ?: error("Invalid extraction destination")
                val normalizedCleanDestination = FileUtil.normalizeAbsolutePath(cleanDestination)
                    ?: error("Invalid clean destination")
                check(FileUtil.isDescendant(normalizedDestination, normalizedCleanDestination)) { "Unsafe clean destination" }
                check(FileUtil.deleteRecursively(normalizedCleanDestination)) { "Unable to clean restore destination" }
            }
            val extraction = extractApproved(
                source = stableSource,
                destination = destination,
                compression = compression,
                entriesFile = inspection.entriesFile,
                workspace = normalizedWorkspace,
                preservePermissions = preservePermissions,
                ignoreModificationTime = ignoreModificationTime,
            )
            ArchiveExtractionParcelable(
                code = extraction.first,
                output = extraction.second,
                skippedEntries = inspection.skippedEntries,
                pendingLinks = inspection.pendingLinks,
            )
        }
    }.getOrElse { error ->
        ArchiveExtractionParcelable(
            code = -1,
            output = listOf(error.message ?: "Unable to extract archive"),
            skippedEntries = 0,
            pendingLinks = 0,
        )
    }

    private fun inspect(
        source: String,
        compression: String,
        workspace: String,
        requiredPrefix: String,
        excludedPathPrefixes: Array<String>,
        excludedNamePrefixes: Array<String>,
        cleanRestore: Boolean,
    ): Inspection {
        val entriesFile = File(workspace, ENTRIES_FILE)
        openArchive(source, compression, workspace).use { archive ->
            BufferedOutputStream(FileOutputStream(entriesFile)).use { entries ->
                val inspection = TarHeaderInspector.inspect(
                    input = archive.stream,
                    requiredPrefix = requiredPrefix,
                    excludedPathPrefixes = excludedPathPrefixes.toSet(),
                    excludedNamePrefixes = excludedNamePrefixes.toSet(),
                    cleanRestore = cleanRestore,
                ) { entry -> writeEntry(entries, entry) }
                ArchiveLinkStore.write(File(workspace, LINKS_FILE), inspection.links)
                check(archive.finish()) { archive.error() }
                return Inspection(entriesFile, inspection.skippedEntries, inspection.links.size)
            }
        }
    }

    private fun extractApproved(
        source: String,
        destination: String,
        compression: String,
        entriesFile: File,
        workspace: String,
        preservePermissions: Boolean,
        ignoreModificationTime: Boolean,
    ): Pair<Int, List<String>> {
        val tarArgs = mutableListOf(
            "${context.binDir()}/tar",
            "--totals",
            "--no-recursion",
            "--null",
            "--verbatim-files-from",
            "--no-unquote",
            "--no-overwrite-dir",
        )
        if (!preservePermissions) tarArgs += listOf("--no-same-owner", "--no-same-permissions")
        tarArgs += buildString {
            append("-x")
            if (ignoreModificationTime) append('m')
            if (preservePermissions) append('p')
            append('f')
        }
        tarArgs += source
        when (compression) {
            "gzip" -> tarArgs += "--use-compress-program=${context.binDir()}/busybox gzip"
            "zstd" -> tarArgs += "--use-compress-program=${context.binDir()}/zstd"
        }
        tarArgs += listOf("-C", destination, "-T", entriesFile.path)

        val diagnostics = File(workspace, "tar.log")
        val tar = ProcessBuilder(tarArgs).redirectErrorStream(true).redirectOutput(diagnostics).start()
        return try {
            tar.waitFor() to diagnostics.useLines { it.take(32).toList() }
        } finally {
            tar.destroy()
        }
    }

    private fun openArchive(source: String, compression: String, workspace: String): ArchiveInput {
        return when (compression) {
            "" -> ArchiveInput(FileInputStream(source))
            "gzip" -> ArchiveInput(
                GzipCompressorInputStream.builder()
                    .setInputStream(FileInputStream(source))
                    .setDecompressConcatenated(true)
                    .get(),
            )
            "zstd" -> {
                val errorFile = File(workspace, "zstd-${System.nanoTime()}.log")
                val process = ProcessBuilder("${context.binDir()}/zstd", "-d", "-f", "-c", "--", source)
                    .redirectError(errorFile)
                    .start()
                ArchiveInput(process.inputStream, process, errorFile)
            }
            else -> error("Unsupported archive compression: $compression")
        }
    }

    private fun writeEntry(output: BufferedOutputStream, entry: String) {
        output.write(entry.toByteArray(StandardCharsets.UTF_8))
        output.write(0)
    }

    private data class Inspection(
        val entriesFile: File,
        val skippedEntries: Int,
        val pendingLinks: Int,
    )

    private class ArchiveInput(
        val stream: InputStream,
        private val process: Process? = null,
        private val errorFile: File? = null,
    ) : AutoCloseable {
        fun finish(): Boolean = process?.waitFor()?.let { it == 0 } ?: true

        fun error(): String = errorFile?.readText()?.trim().orEmpty().ifBlank { "Unable to decompress archive" }

        override fun close() {
            stream.close()
            process?.destroy()
            errorFile?.delete()
        }
    }

    companion object {
        const val LINKS_FILE = "links.bin"
        private const val ENTRIES_FILE = "entries"
    }
}

internal object ArchiveLinkStore {
    private const val MAGIC = 0x49424C4B

    fun write(file: File, links: List<Link>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(links.size)
            links.forEach { link ->
                output.writeByte(link.type.value)
                output.writeString(link.path)
                output.writeString(link.target)
            }
        }
    }

    fun read(file: File, roots: Array<out String>): Sequence<Link> = sequence {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            check(input.readInt() == MAGIC) { "Invalid archive link index" }
            repeat(input.readInt()) {
                val link = Link(LinkType.fromValue(input.readUnsignedByte()), input.readString(), input.readString())
                if (roots.isEmpty() || roots.any { link.path.startsWith("$it/") }) yield(link)
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        check(size in 0..4096) { "Invalid archive link index" }
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }
}
