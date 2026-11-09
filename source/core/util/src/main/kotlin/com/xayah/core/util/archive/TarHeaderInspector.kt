package com.xayah.core.util.archive

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

object TarHeaderInspector {
    enum class LinkType(val value: Int) {
        SYMBOLIC(1),
        HARD(2);

        companion object {
            fun fromValue(value: Int): LinkType = entries.first { it.value == value }
        }
    }

    data class Link(val type: LinkType, val path: String, val target: String)

    data class Result(val links: List<Link>, val skippedEntries: Int)

    fun inspect(
        input: InputStream,
        requiredPrefix: String = "",
        excludedPathPrefixes: Set<String> = emptySet(),
        excludedNamePrefixes: Set<String> = emptySet(),
        cleanRestore: Boolean = false,
        onApprovedEntry: (String) -> Unit,
    ): Result {
        val root = requiredPrefix.removeSuffix("/")
        val links = mutableListOf<Link>()
        val listed = HashSet<String>()
        val tree = if (cleanRestore) RestoreTree() else null
        val withheld = HashSet<String>()
        var skippedEntries = 0
        BufferedInputStream(input).use { buffered ->
            val tar = object : TarArchiveInputStream(buffered) {
                override fun isEOFRecord(record: ByteArray?): Boolean {
                    if (cleanRestore && record == null && !isAtEOF) throw EOFException("Missing tar end marker")
                    return super.isEOFRecord(record)
                }
            }
            while (true) {
                val entry = tar.nextEntry ?: break
                check(entry.isCheckSumOK) { "Invalid tar header: ${entry.name}" }
                check(tar.canReadEntryData(entry)) { "Unsupported tar entry: ${entry.name}" }
                val ordinary = entry.linkFlag in REGULAR_FILE_TYPES || entry.linkFlag == TarConstants.LF_DIR
                if (ordinary && isRootDirectory(entry.name, entry.isDirectory)) {
                    if (requiredPrefix.isEmpty() && listed.add(entry.name.trimEnd('/'))) onApprovedEntry(entry.name)
                    continue
                }
                val normalized = normalize(entry.name) ?: error("Unsafe archive path: ${entry.name}")
                if (root.isNotEmpty()) {
                    check(normalized.substringBefore('/') == root) { "Unexpected archive root: ${entry.name}" }
                    if (normalized == root && requiredPrefix.endsWith('/')) {
                        check(ordinary && entry.isDirectory && entry.size == 0L) { "Expected a directory at archive root: ${entry.name}" }
                    }
                }
                if (isExcluded(normalized, excludedPathPrefixes, excludedNamePrefixes)) continue
                if (ordinary) {
                    tree?.record(normalized, entry.isDirectory)
                    if (listed.add(entry.name.trimEnd('/'))) onApprovedEntry(entry.name)
                    continue
                }
                withheld.add(normalized)
                when {
                    entry.isSymbolicLink -> links += Link(LinkType.SYMBOLIC, normalized, entry.linkName)
                    entry.isLink -> links += Link(LinkType.HARD, normalized, entry.linkName)
                    else -> skippedEntries++
                }
            }
            val padding = ByteArray(DEFAULT_BUFFER_SIZE)
            while (buffered.read(padding) != -1) {
                // Read through the compression trailer before allowing a clean restore.
            }
        }

        // GNU tar selects by name, so a listed name must not select a deferred entry too.
        if (withheld.isNotEmpty()) for (name in listed) {
            check(normalize(name) !in withheld) { "Conflicting tar entries: $name" }
        }
        val symbolicLinks = links.asSequence()
            .filter { it.type == LinkType.SYMBOLIC }
            .mapTo(hashSetOf()) { it.path }
        val safeLinks = links.filter { link ->
            val safe = ancestors(link.path).none(symbolicLinks::contains) && isValidLinkTarget(link.target)
            if (!safe) skippedEntries++
            safe
        }
        return Result(safeLinks, skippedEntries)
    }

    private class RestoreTree {
        private val files = HashSet<String>()
        private val nonEmptyDirectories = HashSet<String>()

        fun record(path: String, directory: Boolean) {
            for (parent in ancestors(path)) {
                check(parent !in files) { "Archive entry has a file as its parent: $path" }
                if (!nonEmptyDirectories.add(parent)) break
            }
            if (directory) files.remove(path) else {
                check(path !in nonEmptyDirectories) { "Archive file replaces a non-empty directory: $path" }
                files.add(path)
            }
        }
    }

    private fun normalize(entry: String): String? {
        if (entry.startsWith('/') || '\u0000' in entry || entry.toByteArray(StandardCharsets.UTF_8).size > MAX_PATH_BYTES) return null
        val segments = entry.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || ".." in segments) return null
        if (segments.any { it.toByteArray(StandardCharsets.UTF_8).size > MAX_NAME_BYTES }) return null
        return segments.joinToString("/")
    }

    private fun isRootDirectory(entry: String, directory: Boolean): Boolean =
        directory && !entry.startsWith('/') && entry.split('/').all { it.isEmpty() || it == "." }

    private fun isExcluded(path: String, pathPrefixes: Set<String>, namePrefixes: Set<String>): Boolean {
        if (pathPrefixes.any { prefix -> path == prefix || path.startsWith("$prefix/") }) return true
        return path.split('/').any { name -> namePrefixes.any(name::startsWith) }
    }

    private fun ancestors(path: String): Sequence<String> = sequence {
        var separator = path.lastIndexOf('/')
        while (separator >= 0) {
            yield(path.substring(0, separator))
            separator = path.lastIndexOf('/', separator - 1)
        }
    }

    private fun isValidLinkTarget(target: String): Boolean =
        target.isNotEmpty() && '\u0000' !in target && target.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES

    private const val MAX_PATH_BYTES = 4095
    private const val MAX_NAME_BYTES = 255
    private val REGULAR_FILE_TYPES = setOf(
        TarConstants.LF_OLDNORM,
        TarConstants.LF_NORMAL,
        TarConstants.LF_CONTIG,
        TarConstants.LF_GNUTYPE_SPARSE,
    )
}
