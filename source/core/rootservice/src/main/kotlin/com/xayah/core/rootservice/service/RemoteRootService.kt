package com.xayah.core.rootservice.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.UserInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.R
import com.xayah.core.rootservice.impl.RemoteRootServiceImpl
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.parcelables.StatFsParcelable
import com.xayah.core.rootservice.parcelables.StorageStatsParcelable
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.rootservice.util.withMainContext
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.FileUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.IOException

data class ArchiveExtraction(
    val result: ShellResult,
    val skippedEntries: Int,
    val pendingLinks: Int,
)

data class DirectoryEntries(val directories: List<String>, val files: List<String>)

class RemoteRootService(private val context: Context) {
    private var mService: IRemoteRootService? = null
    private var binding: CompletableDeferred<IRemoteRootService>? = null
    private val mutex = Mutex()
    private val intent by lazy {
        Intent().apply {
            component = ComponentName(context.packageName, RemoteRootService::class.java.name)
        }
    }
    val gsonUtil by lazy { GsonUtil() }

    var onFailure: (Throwable) -> Unit = {}

    class RemoteRootService : RootService() {
        init {
            if (Process.myUid() == 0)
                System.loadLibrary("nativelib")
        }

        private val service by lazy { RemoteRootServiceImpl(applicationContext) }

        override fun onBind(intent: Intent): IBinder = service

        override fun onDestroy() {
            service.closePauses()
            super.onDestroy()
        }
    }

    private fun bindService(): CompletableDeferred<IRemoteRootService> {
        val pending = CompletableDeferred<IRemoteRootService>()
        binding = pending
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (binding !== pending) {
                    RootService.unbind(this)
                    return
                }
                val remote = IRemoteRootService.Stub.asInterface(service)
                mService = remote
                pending.complete(remote)
            }

            private fun disconnected(reason: String) {
                if (binding === pending) {
                    mService = null
                    binding = null
                }
                pending.completeExceptionally(RemoteException(reason))
            }

            override fun onServiceDisconnected(name: ComponentName) = disconnected("Root service disconnected")
            override fun onBindingDied(name: ComponentName) = disconnected("Root service binding died")
            override fun onNullBinding(name: ComponentName) = disconnected("Root service returned an empty binding")
        }
        try {
            RootService.bind(intent, connection)
        } catch (e: Exception) {
            binding = null
            throw e
        }
        return pending
    }

    private suspend fun getService(): IRemoteRootService = withMainContext {
        val pending = mutex.withLock {
            mService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withMainContext it }
            binding?.takeUnless { it.isCompleted } ?: run {
                check(withIOContext { Shell.getShell().isRoot }) { context.getString(R.string.root_access_unavailable) }
                bindService()
            }
        }
        withTimeoutOrNull(15_000) { pending.await() } ?: throw RemoteException(context.getString(R.string.root_service_timeout))
    }

    suspend fun startActivity(intent: Intent, userId: Int = 0): Boolean = withIOContext {
        val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val rooted = runCatching { getService().startActivity(launchIntent, userId) }.onFailure(onFailure).getOrDefault(false)
        rooted || withMainContext {
            runCatching { context.startActivity(launchIntent) }.isSuccess
        }
    }

    suspend fun readStatFs(path: String): StatFsParcelable = runCatching { getService().readStatFs(path) }.onFailure(onFailure).getOrElse { StatFsParcelable() }

    suspend fun mkdirs(path: String): Boolean = runCatching { getService().mkdirs(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun createDirectory(path: String): Boolean =
        runCatching { getService().createDirectory(path) }.onFailure(onFailure).getOrDefault(false)

    suspend fun mkdirsWithin(root: String, path: String): Boolean =
        runCatching { getService().mkdirsWithin(root, path) }.onFailure(onFailure).getOrElse { false }

    suspend fun copyRecursively(path: String, targetPath: String, overwrite: Boolean): Boolean =
        runCatching { getService().copyRecursively(path, targetPath, overwrite) }.onFailure(onFailure).getOrElse { false }

    suspend fun copyTo(path: String, targetPath: String, overwrite: Boolean): Boolean =
        runCatching { getService().copyTo(path, targetPath, overwrite) }.onFailure(onFailure).getOrElse { false }

    suspend fun exportApks(paths: List<String>, targetPath: String): Boolean = withIOContext {
        runCatching { getService().exportApks(paths.toTypedArray(), targetPath) }.onFailure(onFailure).getOrDefault(false)
    }

    suspend fun hasZipEntry(path: String, entries: List<String>): Boolean =
        runCatching { getService().hasZipEntry(path, entries.toTypedArray()) }.onFailure(onFailure).getOrElse { false }

    suspend fun createHardLink(path: String, targetPath: String): Boolean =
        runCatching { getService().createHardLink(path, targetPath) }.onFailure(onFailure).getOrElse { false }

    suspend fun restoreArchiveLinks(linkDir: String, destination: String, roots: List<String> = emptyList()): Int =
        getService().restoreArchiveLinks(linkDir, destination, roots.toTypedArray())

    suspend fun extractArchive(
        source: String,
        destination: String,
        compression: String,
        workspace: String,
        cleanDestination: String = "",
        requiredPrefix: String = "",
        excludedPathPrefixes: List<String> = emptyList(),
        excludedNamePrefixes: List<String> = emptyList(),
        preservePermissions: Boolean = true,
        ignoreModificationTime: Boolean = true,
    ): ArchiveExtraction = runCatching {
        getService().extractArchive(
            source,
            destination,
            compression,
            workspace,
            cleanDestination,
            requiredPrefix,
            excludedPathPrefixes.toTypedArray(),
            excludedNamePrefixes.toTypedArray(),
            preservePermissions,
            ignoreModificationTime,
        ).let { result ->
            ArchiveExtraction(
                result = ShellResult(
                    code = result.code,
                    input = listOf(source),
                    out = result.output,
                ),
                skippedEntries = result.skippedEntries,
                pendingLinks = result.pendingLinks,
            )
        }
    }.onFailure(onFailure).getOrElse { error ->
        ArchiveExtraction(
            result = ShellResult(
                code = -1,
                input = listOf(source),
                out = listOf(error.message ?: "Unable to extract archive"),
            ),
            skippedEntries = 0,
            pendingLinks = 0,
        )
    }

    suspend fun renameTo(src: String, dst: String): Boolean = runCatching { getService().renameTo(src, dst) }.onFailure(onFailure).getOrElse { false }

    suspend fun writeText(text: String, dst: String): Boolean = writeBytes(text.toByteArray(), dst)

    suspend fun writeBytes(bytes: ByteArray, dst: String): Boolean = runCatching {
        val tmpFile = File.createTempFile("write-", null, context.cacheDir)
        try {
            tmpFile.writeBytes(bytes)
            val service = getService()
            service.mkdirs(PathUtil.getParentPath(dst)) && service.replaceFile(tmpFile.path, dst)
        } finally {
            tmpFile.delete()
        }
    }.onFailure(onFailure).getOrElse { false }

    suspend fun existsChecked(path: String): Boolean = getService().exists(path)

    suspend fun exists(path: String): Boolean = runCatching { existsChecked(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun createNewFile(path: String): Boolean = runCatching { getService().createNewFile(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun deleteRecursively(path: String): Boolean = runCatching { getService().deleteRecursively(path) }.onFailure(onFailure).getOrElse { false }

    suspend fun removeEntry(path: String, directory: Boolean = false): Boolean =
        runCatching { getService().removeEntry(path, directory) }.onFailure(onFailure).getOrDefault(false)

    suspend fun listFilePaths(path: String, listFiles: Boolean = true, listDirs: Boolean = true): List<String> =
        runCatching { getService().listFilePaths(path, listFiles, listDirs) }.onFailure(onFailure).getOrElse { listOf() }

    private fun <T> readFromParcel(pfd: ParcelFileDescriptor, block: (Parcel) -> T): T {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return block(parcel)
        } finally {
            parcel.recycle()
        }
    }

    suspend fun readDirectory(path: String, includeFiles: Boolean, strict: Boolean = true): DirectoryEntries {
        val pfd = getService().readDirectory(path, includeFiles, strict) ?: throw IOException("Cannot read directory: $path")
        return readFromParcel(pfd) { DirectoryEntries(it.createStringArrayList().orEmpty(), it.createStringArrayList().orEmpty()) }
    }

    suspend fun readTextIfPresent(path: String): String? = readFromParcel(getService().readText(path)) { it.readString() }

    suspend fun readTextChecked(path: String): String = readTextIfPresent(path) ?: throw IOException("File not found: $path")

    suspend fun readText(path: String): String = runCatching { readTextChecked(path) }.onFailure(onFailure).getOrDefault("")

    suspend fun readBytes(src: String): ByteArray = runCatching {
        val pfd = getService().readBytes(src)
        var bytes = ByteArray(0)
        readFromParcel(pfd) {
            bytes = ByteArray(it.readInt())
            it.readByteArray(bytes)
        }
        bytes
    }.onFailure(onFailure).getOrElse { ByteArray(0) }

    suspend fun calculateSize(path: String): Long = runCatching { getService().calculateSize(path) }.onFailure(onFailure).getOrElse { 0 }

    suspend fun fileLengthIfPresent(path: String): Long? = getService().fileLength(path).takeIf { it >= 0 }

    suspend fun fileLength(path: String): Long? = runCatching { fileLengthIfPresent(path) }.onFailure(onFailure).getOrNull()

    suspend fun fileGroupId(path: String): UInt = getService().fileGroupId(path).toUInt()

    suspend fun getInstalledPackagesAsUser(flags: Int, userId: Int): Result<List<PackageInfo>> = runCatching {
        val pfd = getService().getInstalledPackagesAsUser(flags, userId)
        readFromParcel(pfd) { checkNotNull(it.createTypedArrayList(PackageInfo.CREATOR)) }
    }.onFailure(onFailure)

    suspend fun getPackageInfoAsUser(packageName: String, flags: Int, userId: Int) =
        runCatching { getService().getPackageInfoAsUser(packageName, flags, userId) }.onFailure(onFailure).getOrNull()

    suspend fun grantRuntimePermission(packageName: String, permName: String, user: UserHandle) =
        runCatching { getService().grantRuntimePermission(packageName, permName, user) }.withLog()

    suspend fun revokeRuntimePermission(packageName: String, permName: String, user: UserHandle) =
        runCatching { getService().revokeRuntimePermission(packageName, permName, user) }.withLog()

    suspend fun getPermissionFlags(packageName: String, permName: String, user: UserHandle) =
        runCatching { getService().getPermissionFlags(packageName, permName, user) }.onFailure(onFailure).getOrElse { 0 }

    suspend fun updatePermissionFlags(packageName: String, permName: String, user: UserHandle, flagMask: Int, flagValues: Int) =
        runCatching { getService().updatePermissionFlags(packageName, permName, user, flagMask, flagValues) }.onFailure(onFailure)

    suspend fun getPackageSourceDir(packageName: String, userId: Int): List<String> =
        runCatching { getService().getPackageSourceDir(packageName, userId) }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun queryInstalled(packageName: String, userId: Int): Boolean =
        runCatching { getService().queryInstalled(packageName, userId) }.onFailure(onFailure).getOrElse { false }

    suspend fun getPackageUid(packageName: String, userId: Int): Int =
        runCatching { getService().getPackageUid(packageName, userId) }.onFailure(onFailure).getOrElse { -1 }

    suspend fun getUserHandle(userId: Int): UserHandle? = runCatching { getService().getUserHandle(userId) }.onFailure(onFailure).getOrNull()

    suspend fun queryStatsForPackage(packageInfo: PackageInfo, user: UserHandle): StorageStatsParcelable? =
        runCatching { getService().queryStatsForPackage(packageInfo, user) }.onFailure(onFailure).getOrNull()

    suspend fun getUsers(): List<UserInfo> = runCatching { getService().users }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun walkFileTree(path: String): List<PathParcelable> = runCatching {
        val pfd = getService().walkFileTree(path)
        val list = mutableListOf<PathParcelable>()
        readFromParcel(pfd) {
            it.readTypedList(list, PathParcelable.CREATOR)
        }
        list
    }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun getPackageArchiveInfo(path: String): PackageInfo? = runCatching { getService().getPackageArchiveInfo(path) }.onFailure(onFailure).getOrNull()

    suspend fun getPackageSsaidAsUser(packageName: String, uid: Int, userId: Int): String =
        runCatching { getService().getPackageSsaidAsUser(packageName, uid, userId) ?: "" }.onFailure(onFailure).getOrElse { "" }

    suspend fun setPackageSsaidAsUser(packageName: String, uid: Int, userId: Int, ssaid: String) =
        runCatching { getService().setPackageSsaidAsUser(packageName, uid, userId, ssaid) }.onFailure(onFailure)

    suspend fun setDisplayPowerMode(mode: Int) =
        runCatching { getService().setDisplayPowerMode(mode) }.onFailure(onFailure)

    suspend fun getScreenOffTimeout() =
        runCatching { getService().getScreenOffTimeout() }.onFailure(onFailure)

    suspend fun setScreenOffTimeout(timeout: Int) =
        runCatching { getService().setScreenOffTimeout(timeout) }.onFailure(onFailure)

    suspend fun forceStopPackageAsUser(packageName: String, userId: Int) =
        runCatching { getService().forceStopPackageAsUser(packageName, userId) }.onFailure(onFailure)

    suspend fun killPackageProcesses(packageName: String, userId: Int) = getService().killPackageProcesses(packageName, userId)

    suspend fun forceStopPackageIfRunning(packageName: String, userId: Int): Boolean? =
        runCatching { getService().forceStopPackageIfRunning(packageName, userId) }.onFailure(onFailure).getOrNull()

    suspend fun pausePackage(packageName: String, userId: Int): AutoCloseable? = runCatching {
        val service = getService()
        val owner = Binder()
        val pause = AutoCloseable {
            runCatching { service.resumePackage(owner) }
                .recoverCatching { service.resumePackage(owner) }
                .onFailure(onFailure)
        }
        try {
            service.pausePackage(packageName, userId, owner)
            pause
        } catch (error: Exception) {
            pause.close()
            throw error
        }
    }.getOrElse {
        if (it is CancellationException) throw it
        onFailure(it)
        null
    }

    suspend fun uninstallPackageAsUser(packageName: String, userId: Int): Boolean =
        runCatching { getService().uninstallPackageAsUser(packageName, userId) }.onFailure(onFailure).getOrDefault(false)

    suspend fun uninstallPackageKeepingDataAsUser(packageName: String, userId: Int): Boolean =
        runCatching { getService().uninstallPackageKeepingDataAsUser(packageName, userId) }.onFailure(onFailure).getOrDefault(false)

    suspend fun clearPackageDataAsUser(packageName: String, userId: Int): Boolean =
        runCatching { getService().clearPackageDataAsUser(packageName, userId) }.onFailure(onFailure).getOrDefault(false)

    suspend fun clearPackageCacheAsUser(packageName: String, userId: Int): Boolean =
        runCatching { getService().clearPackageCacheAsUser(packageName, userId) }.onFailure(onFailure).getOrDefault(false)

    suspend fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int, userId: Int, callingPackage: String?) =
        runCatching { getService().setApplicationEnabledSetting(packageName, newState, flags, userId, callingPackage) }.onFailure(onFailure)

    suspend fun getApplicationEnabledSetting(packageName: String, userId: Int): Int? =
        runCatching { getService().getApplicationEnabledSetting(packageName, userId) }.onFailure(onFailure).getOrNull()

    suspend fun getPermissions(packageInfo: PackageInfo): List<PackagePermission> =
        runCatching { getService().getPermissions(packageInfo) }.onFailure(onFailure).getOrElse { listOf() }

    suspend fun setOpsMode(code: Int, uid: Int, packageName: String?, mode: Int) =
        runCatching { getService().setOpsMode(code, uid, packageName, mode) }.withLog()

    suspend fun calculateMD5(src: String): String? =
        runCatching { getService().calculateMD5(src) }.onFailure(onFailure).getOrNull()

    suspend fun calculateSHA256(src: String): String? =
        runCatching { getService().calculateSHA256(src) }.onFailure(onFailure).getOrNull()

    suspend fun writeJson(data: Any, dst: String): ShellResult = runCatching {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        writeText(text = gsonUtil.toJson(data), dst = dst).also {
            isSuccess = it
            if (isSuccess) {
                out.add("Succeed to write configs: $dst")
            } else {
                out.add("Failed to write configs: $dst")
            }
        }
        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }.onFailure(onFailure).getOrElse { ShellResult(code = -1, input = listOf(), out = listOf()) }

    suspend inline fun <reified T> readJson(src: String): T? = runCatching<T?> {
        val json = readText(path = src)
        gsonUtil.fromJson<T>(json, object : TypeToken<T>() {}.type)
    }.onFailure(onFailure).getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> writeProtoBuf(data: T, dst: String): ShellResult = runCatching {
        var isSuccess: Boolean
        val out = mutableListOf<String>()

        val bytes = ProtoBuf.encodeToByteArray<T>(data)
        writeBytes(bytes = bytes, dst = dst).also {
            isSuccess = it
            if (isSuccess) {
                out.add("Succeed to write configs: $dst")
            } else {
                out.add("Failed to write configs: $dst")
            }
        }
        ShellResult(code = if (isSuccess) 0 else -1, input = listOf(), out = out)
    }.onFailure(onFailure).getOrElse { ShellResult(code = -1, input = listOf(), out = listOf()) }

    /**
     * @see <a href="https://github.com/Kotlin/kotlinx.serialization/issues/2185#issuecomment-1420612365">Unsupported start group or end group wire type: 7</a>
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend inline fun <reified T> readProtoBuf(src: String): T? = runCatching<T?> {
        val bytes = readBytes(src = src)
        ProtoBuf.decodeFromByteArray<T>(bytes)
    }.onFailure(onFailure).getOrNull()
}
