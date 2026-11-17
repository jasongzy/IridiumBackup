package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.network.R
import com.xayah.core.network.util.getExtraEntity
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.withMainContext
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.ui.model.PickerType
import java.io.IOException
import java.io.File
import java.io.OutputStream

internal const val CONNECT_TIMEOUT_MS = 15_000
internal const val IO_TIMEOUT_MS = 120_000

internal fun checkTransferSize(path: String, expected: Long, transferred: Long) {
    if (expected >= 0 && transferred != expected) throw IOException("Transfer size mismatch: $path ($transferred of $expected bytes)")
}

interface CloudClient {
    fun connect()
    fun disconnect()
    fun mkdir(dst: String)
    fun mkdirRecursively(dst: String)
    fun renameTo(src: String, dst: String, replace: Boolean = false)
    fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit)
    fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) {
        File(dst, src.substringAfterLast('/')).outputStream().use { read(src, it, onDownloading) }
    }
    fun read(src: String, output: OutputStream, onDownloading: (written: Long, total: Long) -> Unit)
    fun deleteFile(src: String)
    fun removeEmptyDirectory(src: String)
    fun deleteRecursively(src: String)
    fun listFiles(src: String): DirChildrenParcelable
    fun walkFileTree(src: String): List<PathParcelable>
    fun exists(src: String): Boolean
    suspend fun testConnection() {
        connect()
        disconnect()
    }
    suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit)
}

internal suspend fun CloudClient.pickDirectory(
    context: Context,
    initialFolder: String = "",
    remotePath: (String) -> String,
    list: (String) -> DirChildrenParcelable = ::listFiles,
    create: (String) -> Unit = ::mkdirRecursively,
): String {
    val root = listOf("${context.getString(R.string.cloud)}:")
    fun resolve(path: String) = remotePath(path.substringAfter('/', ""))
    val picker = PickYouLauncher(
        checkPermission = false,
        title = context.getString(R.string.select_target_directory),
        pickerType = PickerType.DIRECTORY,
        rootPathList = root,
        defaultPathList = if (initialFolder.isEmpty()) root else root + initialFolder,
        traverseBackend = { list(resolve(it)) },
        mkdirsBackend = { parent, child ->
            child.isNotEmpty() && child != "." && child != ".." && child.none { it in "/\r\n" } &&
                runCatching { create(resolve("$parent/$child")) }.isSuccess
        },
    )
    connect()
    try {
        return resolve(withMainContext { picker.awaitLaunch(context) })
    } finally {
        disconnect()
    }
}

fun CloudEntity.getCloud() = when (this.type) {
    CloudType.FTP -> {
        val extra = getExtraEntity<FTPExtra>()!!
        FTPClientImpl(this, extra)
    }

    CloudType.WEBDAV -> {
        val extra = getExtraEntity<WebDAVExtra>()!!
        WebDAVClientImpl(this, extra)
    }

    CloudType.SMB -> {
        val extra = getExtraEntity<SMBExtra>()!!
        SMBClientImpl(this, extra)
    }

    CloudType.SFTP -> {
        val extra = getExtraEntity<SFTPExtra>()!!
        SFTPClientImpl(this, extra)
    }
}
