package com.xayah.core.data.repository

import com.xayah.core.model.BackupManifest
import com.xayah.core.model.CompressionType
import com.xayah.core.rootservice.service.RemoteRootService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal val BackupManifest.apkCompression: CompressionType?
    get() = CompressionType.entries.firstOrNull { type -> files.orEmpty().any { it.name == "apk.${type.suffix}" } }

internal suspend fun <T> RemoteRootService.withBackupApks(
    cacheDir: File,
    revisionDir: String,
    compression: CompressionType?,
    inspect: suspend (List<String>) -> T,
): T? {
    if (compression == null) return null
    val workspace = File(cacheDir, "inspect-apk-${UUID.randomUUID()}")
    val extracted = File(workspace, "apks")
    try {
        if (!mkdirs(extracted.path)) return null
        val archive = "$revisionDir/apk.${compression.suffix}"
        if (!extractArchive(archive, extracted.path, compression.decompressPara, workspace.path).result.isSuccess) return null
        return inspect(listFilePaths(extracted.path, listDirs = false).filter { it.endsWith(".apk", ignoreCase = true) })
    } finally {
        withContext(NonCancellable) { deleteRecursively(workspace.path) }
    }
}
