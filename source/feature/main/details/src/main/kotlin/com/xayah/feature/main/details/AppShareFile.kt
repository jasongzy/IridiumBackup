package com.xayah.feature.main.details

import androidx.core.content.pm.PackageInfoCompat
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.rootservice.service.RemoteRootService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun prepareAppShareFile(cacheDir: File, app: PackageEntity, rootService: RemoteRootService): File? {
    val info = rootService.getPackageInfoAsUser(app.packageName, 0, app.userId) ?: return null
    val installed = info.applicationInfo ?: return null
    val sources = listOf(installed.sourceDir) + installed.splitSourceDirs.orEmpty()
    val shareDir = File(cacheDir, "shared_apk/${UUID.randomUUID()}")
    if (!shareDir.mkdirs()) return null
    val label = app.packageInfo.label.ifBlank { app.packageName }
    val version = info.versionName?.takeIf(String::isNotBlank) ?: PackageInfoCompat.getLongVersionCode(info).toString()
    val fileName = "$label-$version".replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { app.packageName }
    val target = File(shareDir, "$fileName.${if (sources.size == 1) "apk" else "apks"}")
    var ready = false
    try {
        if (!rootService.exportApks(sources, target.path)) return null
        currentCoroutineContext().ensureActive()
        ready = true
        return target
    } finally {
        if (!ready) withContext(NonCancellable) { rootService.deleteRecursively(shareDir.path) }
    }
}
