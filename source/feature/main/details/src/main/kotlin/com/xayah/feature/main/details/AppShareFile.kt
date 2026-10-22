package com.xayah.feature.main.details

import com.xayah.core.model.database.PackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun prepareAppShareFile(cacheDir: File, app: PackageEntity, rootService: RemoteRootService): File? {
    val sources = rootService.getPackageSourceDir(app.packageName, app.userId)
    if (sources.isEmpty()) return null
    val shareDir = File(cacheDir, "shared_apk/${UUID.randomUUID()}")
    if (!shareDir.mkdirs()) return null
    val stagingDir = File(shareDir, "staging")
    val label = app.packageInfo.label.ifBlank { app.packageName }
    val version = app.packageInfo.versionName.ifBlank { app.packageInfo.versionCode.toString() }
    val fileName = "$label-$version".replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { app.packageName }
    val target = File(shareDir, "$fileName.${if (sources.size == 1) "apk" else "apks"}")
    var ready = false
    try {
        if (sources.size == 1) {
            if (!rootService.copyTo(sources.first(), target.path, true)) return null
            rootService.setAllPermissions(target.path)
        } else {
            if (!stagingDir.mkdirs()) return null
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { output ->
                output.setLevel(Deflater.NO_COMPRESSION)
                for ((index, source) in sources.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val staged = File(stagingDir, if (index == 0) "base.apk" else File(source).name)
                    if (!rootService.copyTo(source, staged.path, true)) return null
                    rootService.setAllPermissions(staged.path)
                    output.putNextEntry(ZipEntry(staged.name).apply { time = staged.lastModified() })
                    staged.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        currentCoroutineContext().ensureActive()
        ready = target.isFile
        return target.takeIf { ready }
    } catch (_: IOException) {
        return null
    } finally {
        withContext(NonCancellable) {
            rootService.deleteRecursively(if (ready) stagingDir.path else shareDir.path)
        }
    }
}
