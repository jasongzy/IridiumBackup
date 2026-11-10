package com.xayah.core.rootservice.service

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.pm.PackageManager
import android.os.UserHandle
import com.xayah.core.model.database.PackagePermission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private fun PackagePermission.restorableMode(current: PackagePermission) =
    mode.takeIf { op != AppOpsManagerHidden.OP_NONE && op == current.op }

private fun PackagePermission.matches(current: PackagePermission): Boolean {
    val expectedMode = restorableMode(current)
    return isGranted == current.isGranted && (expectedMode == null || expectedMode == (current.mode ?: AppOpsManager.MODE_DEFAULT))
}

suspend fun RemoteRootService.restorePermissions(packageName: String, userId: Int, saved: List<PackagePermission>): List<String> {
    if (saved.isEmpty()) return emptyList()
    val info = getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
    val uid = info?.applicationInfo?.uid ?: return saved.map { it.name }
    val user = UserHandle.getUserHandleForUid(uid)
    val requested = info.requestedPermissions.orEmpty().toSet()
    val applicable = saved.filter { it.name in requested }
    var observed = getPermissions(info)?.associateBy { it.name }.orEmpty()
    var attempted = false
    for (permission in applicable) {
        currentCoroutineContext().ensureActive()
        val current = observed[permission.name] ?: continue
        if (permission.matches(current)) continue
        attempted = true
        if (current.isGranted != permission.isGranted) {
            setPermissionGranted(packageName, permission.name, user, permission.isGranted)
        }
        permission.restorableMode(current)?.let { setOpsMode(current.op, uid, packageName, it) }
    }
    if (attempted) {
        observed = getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
            ?.let { getPermissions(it) }?.associateBy { it.name }.orEmpty()
    }
    return applicable.filterNot { permission -> observed[permission.name]?.let(permission::matches) == true }.map { it.name }
}
