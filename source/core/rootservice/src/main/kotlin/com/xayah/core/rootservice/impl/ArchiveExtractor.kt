package com.xayah.core.rootservice.impl

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import java.util.UUID

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
        return withArchive(source, compression, workspace) { stream ->
            BufferedOutputStream(FileOutputStream(entriesFile)).use { entries ->
                val inspection = TarHeaderInspector.inspect(
                    input = stream,
                    requiredPrefix = requiredPrefix,
                    excludedPathPrefixes = excludedPathPrefixes.toSet(),
                    excludedNamePrefixes = excludedNamePrefixes.toSet(),
                    cleanRestore = cleanRestore,
                ) { entry -> writeEntry(entries, entry) }
                ArchiveLinkStore.write(File(workspace, LINKS_FILE), inspection.links)
                Inspection(entriesFile, inspection.skippedEntries, inspection.links.size)
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

    private fun <T> withArchive(source: String, compression: String, workspace: String, read: (InputStream) -> T): T =
        when (compression) {
            "" -> FileInputStream(source).use(read)
            "gzip" -> FileInputStream(source).use { file ->
                GzipCompressorInputStream.builder().setInputStream(file).setDecompressConcatenated(true).get().use(read)
            }
            "zstd" -> {
                val errors = File(workspace, "zstd.log")
                val process = ProcessBuilder("${context.binDir()}/zstd", "-d", "-f", "-c", "--", source)
                    .redirectError(errors).start()
                try {
                    process.inputStream.use(read).also {
                        check(process.waitFor() == 0) { errors.readText().trim().ifBlank { "Unable to decompress archive" } }
                    }
                } finally {
                    process.destroy()
                    errors.delete()
                }
            }
            else -> error("Unsupported archive compression: $compression")
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

    companion object {
        const val LINKS_FILE = "links.bin"
        private const val ENTRIES_FILE = "entries"
    }
}

internal fun restoreArchiveLink(link: Link, root: String): Boolean {
    val relative = TarHeaderInspector.normalizeEntryPath(link.path) ?: return false
    val destination = File(root, relative)
    val hard = link.type == LinkType.HARD
    val target = if (hard && !link.target.startsWith('/')) File(root, link.target).path else link.target
    if (hard && lstatMode(target)?.let(OsConstants::S_ISREG) != true) return false
    val parent = destination.parentFile!!
    if (!ensureDirectories(root, parent.path)) return false
    val mode = lstatMode(destination.path)
    if (mode != null && !OsConstants.S_ISLNK(mode)) return false
    val output = if (mode == null) destination else File(parent, ".restore-link-${UUID.randomUUID()}")
    return try {
        when (link.type) {
            LinkType.SYMBOLIC -> Os.symlink(target, output.path)
            LinkType.HARD -> Os.link(target, output.path)
        }
        if (output != destination) try {
            Os.rename(output.path, destination.path)
        } finally {
            output.delete()
        }
        true
    } catch (_: ErrnoException) {
        false
    }
}

internal fun ensureDirectories(root: String, path: String): Boolean {
    val normalizedRoot = FileUtil.normalizeAbsolutePath(root) ?: return false
    val normalizedPath = FileUtil.normalizeAbsolutePath(path) ?: return false
    if (normalizedPath != normalizedRoot && !FileUtil.isDescendant(normalizedRoot, normalizedPath)) return false
    val relative = normalizedPath.removePrefix(normalizedRoot).trimStart('/')
    var current = normalizedRoot
    if (lstatMode(current) == null && !File(current).mkdirs()) return false
    if (!isDirectory(current)) return false
    relative.split('/').filter(String::isNotEmpty).forEach { segment ->
        current = "$current/$segment"
        val mode = lstatMode(current)
        if (mode == null) {
            if (runCatching { Os.mkdir(current, 493) }.isFailure) return false
        } else if (!OsConstants.S_ISDIR(mode)) {
            return false
        }
    }
    return true
}

private fun isDirectory(path: String): Boolean = lstatMode(path)?.let(OsConstants::S_ISDIR) == true

private fun lstatMode(path: String): Int? = try {
    Os.lstat(path).st_mode
} catch (_: ErrnoException) {
    null
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
