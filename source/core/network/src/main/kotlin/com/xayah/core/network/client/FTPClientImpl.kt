package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import com.xayah.core.network.io.CountingInputStreamImpl
import com.xayah.core.network.io.CountingOutputStreamImpl
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Duration
import javax.security.auth.login.LoginException

class FTPClientImpl(private val entity: CloudEntity, private val extra: FTPExtra) : CloudClient {
    private var client: FTPClient? = null

    private fun log(msg: () -> String) = LogUtil.log("FTPClientImpl", msg())

    private fun <T> withClient(block: (FTPClient) -> T): T = block(checkNotNull(client))

    override fun connect() {
        val connection = object : FTPClient() {
            override fun sendCommand(command: String, args: String?): Int {
                if (args != null && ('\r' in args || '\n' in args)) throw IOException("FTP command arguments cannot contain line breaks")
                return super.sendCommand(command, args)
            }
        }
        try {
            connection.apply {
                autodetectUTF8 = true
                connectTimeout = CONNECT_TIMEOUT_MS
                defaultTimeout = IO_TIMEOUT_MS
                dataTimeout = Duration.ofMillis(IO_TIMEOUT_MS.toLong())
                connect(entity.host, extra.port)
                if (login(entity.user, entity.pass).not()) throw LoginException("FTP login failed ($replyCode).")
                enterLocalPassiveMode()
                val fileType = FTP.BINARY_FILE_TYPE
                if (setFileType(fileType).not()) throw LoginException("Failed to set file type: $fileType.")
            }
            client = connection
        } catch (error: Exception) {
            runCatching { connection.disconnect() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun disconnect() {
        val connection = client ?: return
        client = null
        try {
            if (connection.isConnected) {
                if (!connection.logout()) throw LoginException("FTP logout failed (${connection.replyCode}).")
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun mkdir(dst: String) = withClient { client ->
        log { "mkdir: $dst" }
        if (client.makeDirectory(dst).not()) throw IOException("Failed to mkdir: $dst.")
    }

    override fun mkdirRecursively(dst: String) {
        withClient { client ->
            val original = client.printWorkingDirectory() ?: throw IOException("Unable to read FTP working directory.")
            try {
                if (dst.startsWith('/') && !client.changeWorkingDirectory("/")) {
                    throw IOException("Unable to enter FTP root directory.")
                }
                for (dir in dst.split('/').filter(String::isNotEmpty)) {
                    if (!client.changeWorkingDirectory(dir)) {
                        mkdir(dir)
                        if (!client.changeWorkingDirectory(dir)) throw IOException("Unable to enter FTP directory: $dir")
                    }
                }
            } finally {
                if (!client.changeWorkingDirectory(original)) throw IOException("Unable to restore FTP working directory: $original")
            }
        }
    }

    override fun renameTo(src: String, dst: String) = withClient { client ->
        log { "renameTo: from $src to $dst" }
        if (client.rename(src, dst).not()) throw IOException("Failed to rename file from $src to $dst.")
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "upload: $src to $dstPath" }
        val srcFile = File(src)
        val srcFileSize = srcFile.length()
        val bytes = CountingInputStreamImpl(FileInputStream(srcFile), srcFileSize, onUploading).use { stream ->
            if (!client.storeFile(dstPath, stream)) throw IOException("FTP upload failed (${client.replyCode}): $dstPath")
            checkTransferSize(dstPath, srcFileSize, stream.byteCount)
            stream.byteCount
        }
        onUploading(bytes, bytes)
    }

    override fun download(src: String, dst: String, onDownloading: (written: Long, total: Long) -> Unit) = withClient { client ->
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "download: $src to $dstPath" }
        val dstFile = File(dstPath)
        val size = client.getSize(src)?.trim()?.toLongOrNull() ?: -1L
        val bytes = CountingOutputStreamImpl(dstFile.outputStream(), size, onDownloading).use { stream ->
            if (!client.retrieveFile(src, stream)) throw IOException("FTP download failed (${client.replyCode}): $src")
            checkTransferSize(src, size, stream.byteCount)
            stream.byteCount
        }
        onDownloading(bytes, bytes)
    }

    override fun deleteFile(src: String) = withClient { client ->
        log { "deleteFile: $src" }
        if (client.deleteFile(src).not()) throw IOException("Failed to delete file: $src.")
    }

    override fun removeEmptyDirectory(src: String) = withClient { client ->
        log { "removeDirectory: $src" }
        if (client.removeDirectory(src).not()) throw IOException("Failed to remove dir: $src.")
    }

    private fun FTPClient.listDirectory(src: String): List<FTPFile> {
        val files = listFiles(src)
        if (!FTPReply.isPositiveCompletion(replyCode)) throw IOException("FTP listing failed ($replyCode): $src")
        return files.filterNot { it.name == "." || it.name == ".." }.also { children ->
            if (children.any { it.name.isNullOrEmpty() || '/' in it.name || '\r' in it.name || '\n' in it.name }) {
                throw IOException("Invalid FTP listing: $src")
            }
        }
    }

    private fun findFile(src: String): FTPFile? = withClient { client ->
        client.mlistFile(src) ?: client.listDirectory(PathUtil.getParentPath(src).ifEmpty { "." })
            .firstOrNull { it.name == PathUtil.getFileName(src) }
    }

    private fun listFile(src: String): FTPFile = findFile(src) ?: throw IOException("$src not found.")

    override fun deleteRecursively(src: String) = withClient { client ->
        val srcFile = listFile(src)
        if (srcFile.isDirectory.not()) {
            deleteFile(src)
        } else {
            val dirs = mutableListOf(src)
            val paths = mutableListOf(src)

            // Delete files and append all empty dirs.
            while (paths.isNotEmpty()) {
                val dir = paths.first()
                val files = client.listDirectory(dir)
                for (file in files) {
                    val path = "${dir}/${file.name}"
                    if (file.isDirectory.not()) {
                        deleteFile(path)
                    } else {
                        paths.add(path)
                        dirs.add(path)
                    }
                }
                paths.removeFirstOrNull()
            }

            // Remove reversed empty dirs.
            for (path in dirs.reversed()) removeEmptyDirectory(path)
        }
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        val files = mutableListOf<FileParcelable>()
        val directories = mutableListOf<FileParcelable>()
        withClient { client ->
            val clientFiles = client.listDirectory(src)
            for (file in clientFiles) {
                val creationTime = file.timestamp?.timeInMillis ?: 0L
                val fileParcelable = FileParcelable(file.name, creationTime)
                if (file.isSymbolicLink) {
                    fileParcelable.link = file.link
                }
                if (file.isDirectory) directories.add(fileParcelable)
                else files.add(fileParcelable)
            }
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        val srcFile = listFile(src)
        if (srcFile.isDirectory.not()) {
            pathParcelableList.add(PathParcelable(src))
        } else {
            val files = listFiles(src)
            for (i in files.files) {
                pathParcelableList.add(PathParcelable("${src}/${i.name}"))
            }
            for (i in files.directories) {
                pathParcelableList.addAll(walkFileTree("${src}/${i.name}"))
            }
        }
        return pathParcelableList
    }

    override fun exists(src: String): Boolean = findFile(src) != null

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val remote = pickDirectory(context, remotePath = { it.ifEmpty { "." } })
        onSet(remote, entity.extra)
    }
}
