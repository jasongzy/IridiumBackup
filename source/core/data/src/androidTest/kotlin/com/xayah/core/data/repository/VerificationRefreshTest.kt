package com.xayah.core.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.toPackageEntity
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VerificationRefreshTest {
    @Test
    fun verificationPreservesConcurrentEditsAndDeletedRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val directory = File(context.cacheDir, "verification-refresh-test").apply { mkdirs() }
        try {
            val config = PackageRestoreConfig(
                packageName = "com.example.module", userId = 0, createdAt = 1234, compressionType = CompressionType.TAR,
                label = "Module", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            )
            val restore = config.toPackageEntity("", directory.path).copy(id = 2)
            val installed = restore.copy(id = 1, indexInfo = restore.indexInfo.copy(opType = OpType.BACKUP, preserveId = 0))
            val revisionDir = File(directory, "apps/${restore.archivesRelativeDir}").apply { mkdirs() }
            File(revisionDir, "apk.tar").writeBytes(byteArrayOf(1, 2, 3))
            File(revisionDir.parentFile, "icon.bin").createNewFile()
            val binder = Binder()
            val remote = Proxy.newProxyInstance(
                IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "exists" -> File(args!![0] as String).exists()
                    "mkdirs" -> File(args!![0] as String).let { it.mkdirs() || it.isDirectory }
                    "copyTo" -> {
                        File(args!![0] as String).copyTo(File(args[1] as String), overwrite = args[2] as Boolean)
                        true
                    }
                    "setAllPermissions" -> Unit
                    "calculateSize" -> File(args!![0] as String).length()
                    "calculateSHA256" -> MessageDigest.getInstance("SHA-256")
                        .digest(File(args!![0] as String).readBytes()).joinToString("") { "%02x".format(it) }
                    "listFilePaths" -> File(args!![0] as String).listFiles().orEmpty().filter(File::isFile).map(File::getPath)
                    "readText" -> {
                        val parcel = Parcel.obtain()
                        val file = File.createTempFile("parcel-", ".tmp", directory)
                        try {
                            parcel.writeString(File(args!![0] as String).readText())
                            file.writeBytes(parcel.marshall())
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        } finally {
                            file.delete()
                            parcel.recycle()
                        }
                    }
                    "queryInstalled" -> true
                    "getPackageInfoAsUser" -> PackageInfo().apply {
                        applicationInfo = ApplicationInfo().apply {
                            metaData = Bundle().apply { putInt("xposedminversion", 93) }
                        }
                    }
                    else -> error("Unexpected root operation: ${method.name}")
                }
            }
            val root = RemoteRootService(context).apply {
                javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
            }
            val dao = database.packageDao()
            var removeAfterRead = false
            val edited = mutableMapOf<Long, PackageEntity?>()
            suspend fun editAfterRead(app: PackageEntity) {
                val current = if (removeAfterRead) null else app.copy(
                    extraInfo = app.extraInfo.copy(blocked = true, activated = true),
                    dataStates = app.dataStates.copy(apkState = DataState.NotSelected),
                )
                if (current == null) dao.delete(app.id) else dao.upsert(current)
                edited[app.id] = current
            }
            val interleaved = object : PackageDao by dao {
                override suspend fun query(packageName: String, opType: OpType, userId: Int): PackageEntity? =
                    dao.query(packageName, opType, userId)?.also { editAfterRead(it) }

                override suspend fun queryRevisions(packageName: String, opType: OpType, userId: Int, preserveId: Long): List<PackageEntity> =
                    dao.queryRevisions(packageName, opType, userId, preserveId).also { apps -> apps.forEach { editAfterRead(it) } }
            }
            val paths = PathUtil(context)
            val repository = AppBackupRepository(
                context, database.appBackupDao(),
                PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), interleaved, paths),
                AppIconRepository(context, root, paths), XposedModuleDetector(context, root), root, paths,
            )
            val revision = BackupRevisionEntity(config.packageName, 0, 1234, "1.0", 1, BackupEngine.LEGACY, ":${directory.path}", restore.archivesRelativeDir, 1)
            for (remove in listOf(false, true)) {
                removeAfterRead = remove
                dao.upsert(listOf(installed, restore))
                root.writeJson(config, PathUtil.getPackageRestoreConfigDst(revisionDir.path))
                repository.writeManifest(restore, 1234, revisionDir.path)

                assertEquals(BackupVerificationStatus.VALID, repository.inspectRevision(revision).status)
                assertEquals(
                    listOf(
                        edited[installed.id]?.let { it.copy(packageInfo = it.packageInfo.copy(isXposedModule = true)) },
                        edited[restore.id]?.copy(displayStats = PackageDataStats(apkBytes = 3)),
                    ),
                    listOf(dao.queryById(installed.id), dao.queryById(restore.id)),
                )
            }
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
