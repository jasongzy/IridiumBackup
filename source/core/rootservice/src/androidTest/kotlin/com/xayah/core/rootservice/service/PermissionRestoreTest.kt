package com.xayah.core.rootservice.service

import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.UserHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.rootservice.IRemoteRootService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionRestoreTest {
    @Test
    fun continuesAfterDenialAndReportsIgnoredWritesFromReadback() = runBlocking {
        val current = listOf(PackagePermission("fixed"), PackagePermission("op", op = 7), PackagePermission("granted"))
        val uid = 1_010_001
        val info = PackageInfo().apply {
            applicationInfo = ApplicationInfo().also { it.uid = uid }
            requestedPermissions = current.map { it.name }.toTypedArray()
        }
        val binder = Binder()
        val service = Proxy.newProxyInstance(IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "getPackageInfoAsUser" -> info
                "getPermissions" -> current.map { it.copy() }
                "setOpsMode" -> null
                "grantRuntimePermission" -> {
                    assertEquals(UserHandle.getUserHandleForUid(uid), args[2])
                    if (args[1] == "fixed") throw SecurityException("policy fixed")
                    current.single { it.name == args[1] }.isGranted = true
                    null
                }
                else -> error(method.name)
            }
        }
        val root = RemoteRootService(InstrumentationRegistry.getInstrumentation().targetContext).apply {
            javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, service)
        }
        val saved = listOf(
            PackagePermission("fixed", true),
            PackagePermission("op", op = 7, mode = AppOpsManager.MODE_ALLOWED),
            PackagePermission("granted", true),
            PackagePermission("removed", true),
        )
        assertEquals(listOf("fixed", "op"), root.restorePermissions("test.app", 10, saved))
        assertEquals(true, current.last().isGranted)
    }
}
