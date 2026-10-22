package com.xayah.core.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.DbPreferencesDataSource
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.datastore.saveCustomSUFile
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRefreshTest {
    @Test
    fun refreshesFreezeChangesWithoutRequiringAnAppUpdate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousSu = context.readCustomSUFile().first()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("installed-apps-", ".tmp", context.cacheDir)
        try {
            context.saveCustomSUFile("/system/bin/false")
            val info = PackageInfo().apply {
                packageName = "com.example.refresh"
                versionName = "1.0"
                longVersionCode = 1
                lastUpdateTime = 1234
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.example.refresh"
                    nonLocalizedLabel = "Example"
                    uid = 10001
                    enabled = true
                }
            }
            val user = Class.forName("android.content.pm.UserInfo")
                .getConstructor(Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                .newInstance(0, "Owner", 0)
            val binder = Binder()
            var detailsRead = 0
            val remote = Proxy.newProxyInstance(
                IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "asBinder" -> binder
                    "getUsers" -> listOf(user)
                    "getInstalledPackagesAsUser" -> {
                        val parcel = Parcel.obtain()
                        try {
                            parcel.writeTypedList(listOf(info))
                            file.writeBytes(parcel.marshall())
                        } finally {
                            parcel.recycle()
                        }
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                    "getPackageInfoAsUser" -> info.also { detailsRead++ }
                    "getUserHandle" -> null
                    "getPermissions" -> emptyList<Any>()
                    "getPackageSsaidAsUser" -> ""
                    else -> error("Unexpected root operation: ${method.name}")
                }
            }
            val root = RemoteRootService(context).apply {
                javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
            }
            val paths = PathUtil(context)
            val clouds = CloudRepository(context, root, database.cloudDao())
            val packages = PackageRepository(context, root, clouds, database.packageDao(), paths)
            val modules = XposedModuleDetector(context, root)
            val backups = AppBackupRepository(
                context, database.appBackupDao(), packages, AppIconRepository(context, root, paths), modules, root, paths,
            )
            val preferences = object : DataStore<Preferences> {
                override val data = flowOf(emptyPreferences())
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Unexpected preference write")
            }
            val repository = AppsRepo(
                context, Dispatchers.Default, database.packageDao(), packages, root,
                SettingsDataRepo(DbPreferencesDataSource(preferences)), paths, clouds, backups,
                LabelsRepo(context, Dispatchers.Default, database.labelDao()), modules,
            )
            val app = PackageRestoreConfig(
                packageName = info.packageName, userId = 0, createdAt = 0, compressionType = CompressionType.ZSTD,
                label = "Example", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            ).toPackageEntity("", "").apply {
                indexInfo.opType = OpType.BACKUP
                packageInfo.lastUpdateTime = info.lastUpdateTime
                extraInfo.firstUpdated = true
            }
            database.packageDao().upsert(app)
            for ((enabled, reads) in listOf(true to 0, false to 1, false to 1, true to 2, true to 2)) {
                info.applicationInfo!!.enabled = enabled
                repository.fastInitialize { _, _, _ -> }
                assertEquals(enabled, database.packageDao().queryPkgEntitiesByUserId(OpType.BACKUP, 0).single().extraInfo.enabled)
                assertEquals(reads, detailsRead)
            }
        } finally {
            context.saveCustomSUFile(previousSu)
            database.close()
            file.delete()
        }
    }
}
