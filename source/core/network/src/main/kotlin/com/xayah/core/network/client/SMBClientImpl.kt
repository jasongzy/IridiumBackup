package com.xayah.core.network.client

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.Share
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.xayah.core.model.SmbAuthMode
import com.xayah.core.model.SmbVersion
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SMBExtra
import com.xayah.core.network.R
import com.xayah.core.network.io.CountingOutputStreamImpl
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.GsonUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import java.io.File
import java.io.IOException


class SMBClientImpl(private val entity: CloudEntity, private val extra: SMBExtra) : CloudClient {
    private var client: SMBClient? = null
    private var session: Session? = null
    private var share: Share? = null

    private fun log(msg: () -> String) = LogUtil.log("SMBClientImpl", msg())

    private fun <R> withDiskShare(block: (DiskShare) -> R): R = block(checkNotNull(share as? DiskShare))

    private fun setShare(name: String) {
        share = checkNotNull(session).connectShare(name)
    }

    private fun SmbVersion.toDialect() = when (this) {
        SmbVersion.SMB_2_0_2 -> SMB2Dialect.SMB_2_0_2
        SmbVersion.SMB_2_1 -> SMB2Dialect.SMB_2_1
        SmbVersion.SMB_3_0 -> SMB2Dialect.SMB_3_0
        SmbVersion.SMB_3_0_2 -> SMB2Dialect.SMB_3_0_2
        SmbVersion.SMB_3_1_1 -> SMB2Dialect.SMB_3_1_1
    }

    override fun connect() {
        val dialects = extra.version.map { it.toDialect() }
        val config = SmbConfig.builder()
            .withDialects(dialects)
            .build()
        val smbClient = SMBClient(config)
        client = smbClient
        try {
            smbClient.connect(entity.host, extra.port).also { connection ->
                log { "Dialect: ${connection.connectionContext.negotiatedProtocol.dialect.name}" }
                log { "Mode: ${extra.mode}" }

                val authentication = when (extra.mode) {
                    SmbAuthMode.PASSWORD -> AuthenticationContext(entity.user, entity.pass.toCharArray(), extra.domain)
                    SmbAuthMode.GUEST -> AuthenticationContext.guest()
                    SmbAuthMode.ANONYMOUS -> AuthenticationContext.anonymous()
                }

                session = connection.authenticate(authentication)
                if (extra.share.isNotEmpty()) setShare(extra.share)
            }
        } catch (error: Exception) {
            runCatching { disconnect() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun disconnect() {
        val disk = share
        val connection = client
        share = null
        client = null
        session = null
        try {
            disk?.close()
        } finally {
            connection?.close()
        }
    }

    override fun exists(src: String): Boolean = withDiskShare { disk ->
        try {
            disk.fileExists(src) || disk.folderExists(src)
        } catch (e: SMBApiException) {
            if (e.statusCode != NtStatus.STATUS_NO_SUCH_FILE.value) throw e
            false
        }
    }

    override fun mkdir(dst: String) = withDiskShare { diskShare ->
        log { "mkdir: $dst" }
        diskShare.mkdir(dst)
    }

    override fun mkdirRecursively(dst: String) {
        val dirs = dst.split("/").filter(String::isNotEmpty)
        var currentDir = ""
        for (i in dirs) {
            currentDir += "/$i"
            currentDir = currentDir.trimStart('/')
            if (exists(currentDir).not()) mkdir(currentDir)
        }
    }

    private fun openOnRename(src: String): DiskEntry = withDiskShare { diskShare ->
        diskShare.open(
            src,
            setOf(AccessMask.DELETE),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_RANDOM_ACCESS)
        )
    }

    private fun openFileOnRead(src: String): com.hierynomus.smbj.share.File = withDiskShare { diskShare ->
        diskShare.openFile(
            src,
            setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_RANDOM_ACCESS)
        )
    }

    private fun openFileOnWrite(src: String): com.hierynomus.smbj.share.File = withDiskShare { diskShare ->
        diskShare.openFile(
            src,
            setOf(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_WRITE_EA),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            setOf(SMB2CreateOptions.FILE_RANDOM_ACCESS)
        )
    }

    override fun renameTo(src: String, dst: String) {
        log { "renameTo: from $src to $dst" }
        openOnRename(src).use { it.rename(dst.replace('/', '\\'), false) }
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) = run {
        val name = PathUtil.getFileName(src)
        val dstPath = "${dst.trimEnd('/')}/$name"
        log { "upload: $src to $dstPath" }
        val srcFile = File(src)
        val srcFileSize = srcFile.length()
        val bytes = srcFile.inputStream().use { input ->
            openFileOnWrite(dstPath).use { remote ->
                CountingOutputStreamImpl(remote.outputStream, srcFileSize, onUploading).use { output ->
                    input.copyTo(output)
                }
            }
        }
        checkTransferSize(dstPath, srcFileSize, bytes)
        onUploading(bytes, bytes)
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) = run {
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "download: $src to $dstPath" }
        val bytes = openFileOnRead(src).use { remote ->
            val size = remote.getFileInformation(FileStandardInformation::class.java).endOfFile
            CountingOutputStreamImpl(File(dstPath).outputStream(), size, onDownloading).use { output ->
                remote.read(output)
                checkTransferSize(src, size, output.byteCount)
                output.byteCount
            }
        }
        onDownloading(bytes, bytes)
    }

    override fun deleteFile(src: String) = withDiskShare { diskShare ->
        log { "deleteFile: $src" }
        if (exists(src)) {
            diskShare.rm(src)
        }
    }

    override fun removeEmptyDirectory(src: String) = withDiskShare { diskShare ->
        log { "removeDirectory: $src" }
        diskShare.rmdir(src, false)
    }

    override fun deleteRecursively(src: String) = withDiskShare { diskShare ->
        if (diskShare.fileExists(src)) deleteFile(src)
        else if (diskShare.folderExists(src)) diskShare.rmdir(src, true)
        else throw IOException("$src not found.")
    }

    private fun listPickerFiles(src: String): DirChildrenParcelable {
        if (src.isEmpty()) {
            val transport = SMBTransportFactories.SRVSVC.getTransport(checkNotNull(session))
            val directories = ServerService(transport).shares1.filter { it.type >= 0 && it.type and 0xff == 0 }.sortedBy { it.netName }
                .map { FileParcelable(it.netName, 0L) }
            return DirChildrenParcelable(files = emptyList(), directories = directories)
        }
        setShare(src.substringBefore('/'))
        return listFiles(src.substringAfter('/', ""))
    }

    override fun listFiles(src: String): DirChildrenParcelable = withDiskShare { diskShare ->
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        for (file in diskShare.list(src)) {
            if (file.fileName == "." || file.fileName == "..") continue
            val fileParcelable = FileParcelable(file.fileName, file.creationTime.toEpochMillis())
            if (file.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L) directories.add(fileParcelable)
            else files.add(fileParcelable)
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        withDiskShare { diskShare ->
            if (diskShare.folderExists(src)) {
                val files = listFiles(src)
                for (i in files.files) {
                    pathParcelableList.add(PathParcelable("${src}/${i.name}"))
                }
                for (i in files.directories) {
                    pathParcelableList.addAll(walkFileTree("${src}/${i.name}"))
                }
            } else if (diskShare.fileExists(src)) {
                pathParcelableList.add(PathParcelable(src))
            }
        }
        return pathParcelableList
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val path = pickDirectory(context, extra.share, remotePath = { it }, list = ::listPickerFiles, create = {
            require('/' in it && '\\' !in it)
            setShare(it.substringBefore('/'))
            mkdirRecursively(it.substringAfter('/'))
        })
        require(path.isNotEmpty()) { context.getString(R.string.select_smb_share) }
        onSet(path.substringAfter('/', "/"), GsonUtil().toJson(extra.copy(share = path.substringBefore('/'))))
    }
}
