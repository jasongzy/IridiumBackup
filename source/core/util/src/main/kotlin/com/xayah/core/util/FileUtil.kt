package com.xayah.core.util

import android.annotation.TargetApi
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong

object FileUtil {
    fun listFilePaths(path: String, listFiles: Boolean = true, listDirs: Boolean = true): List<String> = runCatching {
        File(path).listFiles()!!.filter { (it.isFile && listFiles) || (it.isDirectory && listDirs) }.map { it.path }
    }.getOrElse { listOf() }

    fun calculateSize(path: String): Long = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            calculateSizeApi26(path)
        } else {
            calculateSizeApi24(path)
        }
    }

    internal fun calculateSizeApi24(path: String): Long {
        val stat = try {
            Os.lstat(path)
        } catch (_: ErrnoException) {
            return 0
        }
        return if (OsConstants.S_ISDIR(stat.st_mode)) {
            File(path).listFiles()?.sumOf { calculateSizeApi24(it.path) } ?: 0
        } else stat.st_size
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun calculateSizeApi26(path: String): Long {
        val size = AtomicLong(0)
        runCatching {
            Files.walkFileTree(Paths.get(path), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path?, attrs: BasicFileAttributes?): FileVisitResult {
                    if (file != null && attrs != null) {
                        size.addAndGet(attrs.size())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return size.get()
    }

    fun deleteRecursively(path: String): Boolean {
        val normalized = normalizeAbsolutePath(path)?.takeUnless(::isProtectedDeletionPath) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                deleteRecursivelyApi26(Paths.get(normalized))
            } else {
                deleteRecursivelyApi24(File(normalized))
            }
        }.getOrElse { false }
    }

    fun clearEmptyDirectoriesRecursively(path: String): Boolean {
        val normalized = normalizeAbsolutePath(path)?.takeUnless(::isProtectedDeletionPath) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clearEmptyDirectoriesRecursivelyApi26(Paths.get(normalized))
            } else {
                clearEmptyDirectoriesRecursivelyApi24(File(normalized))
            }
        }.getOrElse { false }
    }

    fun isDescendant(parent: String, child: String): Boolean {
        val normalizedParent = normalizeAbsolutePath(parent) ?: return false
        val normalizedChild = normalizeAbsolutePath(child) ?: return false
        return normalizedChild.startsWith("${normalizedParent.removeSuffix("/")}/")
    }

    fun normalizeAbsolutePath(path: String): String? {
        if (!path.startsWith('/') || '\u0000' in path || '\\' in path) return null
        val segments = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return "/${segments.joinToString("/")}"
    }

    @TargetApi(Build.VERSION_CODES.O)
    internal fun deleteRecursivelyApi26(path: Path): Boolean {
        if (hasSymbolicLinkParentApi26(path)) return false
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return true
        var deleted = true
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deleted = Files.deleteIfExists(file) && deleted
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    deleted = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) deleted = false
                    deleted = Files.deleteIfExists(dir) && deleted
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return deleted && Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
    }

    private fun deleteRecursivelyApi24(file: File): Boolean {
        if (hasSymbolicLinkParentApi24(file)) return false
        return deleteEntryApi24(file)
    }

    private fun deleteEntryApi24(file: File): Boolean {
        val mode = try {
            Os.lstat(file.path).st_mode
        } catch (error: ErrnoException) {
            return error.errno == OsConstants.ENOENT
        }
        if (!OsConstants.S_ISDIR(mode)) return file.delete() || !pathExists(file.path)
        val children = file.listFiles() ?: return false
        val childrenDeleted = children.fold(true) { result, child -> deleteEntryApi24(child) && result }
        return childrenDeleted && (file.delete() || !pathExists(file.path))
    }

    @TargetApi(Build.VERSION_CODES.O)
    internal fun clearEmptyDirectoriesRecursivelyApi26(path: Path): Boolean {
        if (hasSymbolicLinkParentApi26(path)) return false
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return true
        var successful = true
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    successful = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        successful = false
                    } else {
                        Files.newDirectoryStream(dir).use { entries ->
                            if (!entries.iterator().hasNext()) successful = Files.deleteIfExists(dir) && successful
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return successful
    }

    private fun clearEmptyDirectoriesRecursivelyApi24(file: File): Boolean {
        if (hasSymbolicLinkParentApi24(file)) return false
        return clearEmptyDirectoryEntryApi24(file)
    }

    private fun clearEmptyDirectoryEntryApi24(file: File): Boolean {
        val mode = try {
            Os.lstat(file.path).st_mode
        } catch (error: ErrnoException) {
            return error.errno == OsConstants.ENOENT
        }
        if (!OsConstants.S_ISDIR(mode)) return true
        val children = file.listFiles() ?: return false
        val childrenCleared = children.fold(true) { result, child -> clearEmptyDirectoryEntryApi24(child) && result }
        val remaining = file.listFiles() ?: return false
        return childrenCleared && (remaining.isNotEmpty() || file.delete() || !pathExists(file.path))
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun hasSymbolicLinkParentApi26(path: Path): Boolean {
        var current = path.root ?: return true
        val parents = path.iterator().asSequence().toList().dropLast(1)
        parents.forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun hasSymbolicLinkParentApi24(file: File): Boolean {
        val parents = generateSequence(file.parentFile, File::getParentFile).toList().asReversed()
        return parents.any { parent ->
            try {
                OsConstants.S_ISLNK(Os.lstat(parent.path).st_mode)
            } catch (_: ErrnoException) {
                true
            }
        }
    }

    private fun pathExists(path: String): Boolean = try {
        Os.lstat(path)
        true
    } catch (error: ErrnoException) {
        error.errno != OsConstants.ENOENT
    }

    private fun isProtectedDeletionPath(path: String): Boolean =
        path.count { it == '/' } <= 3 || path in PROTECTED_DELETION_PATHS

    private val PROTECTED_DELETION_PATHS = setOf(
        "/",
        "/data",
        "/data/data",
        "/data/media",
        "/data/media/0",
        "/data/media/0/Android",
        "/data/media/0/DCIM",
        "/data/media/0/Documents",
        "/data/media/0/Download",
        "/data/media/0/Movies",
        "/data/media/0/Music",
        "/data/media/0/Pictures",
        "/data/user",
        "/data/user/0",
        "/data/user_de",
        "/data/user_de/0",
        "/mnt",
        "/mnt/runtime",
        "/mnt/user",
        "/mnt/user/0",
        "/sdcard",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
        "/storage/emulated/0/Android",
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Movies",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/Pictures",
        "/system",
        "/vendor",
    )

    fun readText(path: String): String = runCatching { File(path).readText() }.getOrElse { "" }
}
