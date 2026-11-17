package com.xayah.core.network.client

import android.content.Context
import com.xayah.core.model.SFTPAuthMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.SFTPExtra
import com.xayah.core.network.io.CountingOutputStreamImpl
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.util.LogUtil
import com.xayah.core.util.PathUtil
import com.xayah.libpickyou.parcelables.DirChildrenParcelable
import com.xayah.libpickyou.parcelables.FileParcelable
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.File
import java.io.OutputStream


class SFTPClientImpl(private val entity: CloudEntity, private val extra: SFTPExtra) : CloudClient {
    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    private fun log(msg: () -> String) = LogUtil.log("SFTPClientImpl", msg())

    private fun <T> withSFTPClient(block: (SFTPClient) -> T) = block(checkNotNull(sftpClient))

    override fun connect() {
        val connection = SSHClient()
        sshClient = connection
        try {
            connection.apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                timeout = CONNECT_TIMEOUT_MS
                addHostKeyVerifier(PromiscuousVerifier())
                connect(entity.host, extra.port)
                socket.soTimeout = 0

                when (extra.mode) {
                    SFTPAuthMode.PASSWORD -> {
                        authPassword(entity.user, entity.pass)
                    }

                    SFTPAuthMode.PUBLIC_KEY -> {
                        val passwordFinder = object : PasswordFinder {
                            var retryCount = 0

                            override fun reqPassword(resource: Resource<*>?): CharArray {
                                return entity.pass.toCharArray()
                            }

                            override fun shouldRetry(resource: Resource<*>?): Boolean {
                                return ++retryCount < 3
                            }
                        }
                        log { "Authenticating with public key..." }
                        val keyProvider = loadKeys(extra.privateKey, null, passwordFinder)
                        log { "Loaded keys!" }
                        authPublickey(entity.user, keyProvider)
                        log { "Authenticated with public key!" }
                    }
                }

                sftpClient = newSFTPClient()
            }
        } catch (error: Exception) {
            runCatching { disconnect() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun disconnect() {
        val sftp = sftpClient
        val ssh = sshClient
        sshClient = null
        sftpClient = null
        try {
            sftp?.close()
        } finally {
            ssh?.disconnect()
        }
    }

    override fun mkdir(dst: String) {
        log { "mkdir: $dst" }
        withSFTPClient { it.mkdir(dst) }
    }

    override fun mkdirRecursively(dst: String) {
        withSFTPClient { it.mkdirs(dst) }
    }

    override fun renameTo(src: String, dst: String, replace: Boolean) {
        log { "Rename $src to $dst" }
        withSFTPClient {
            val flags = if (replace) setOf(RenameFlags.OVERWRITE) else emptySet()
            it.rename(src, dst, flags)
        }
    }

    override fun upload(src: String, dst: String, onUploading: (read: Long, total: Long) -> Unit) {
        val name = PathUtil.getFileName(src)
        val dstPath = "$dst/$name"
        log { "upload: $src to $dstPath" }
        val srcFile = File(src)
        val srcFileSize = srcFile.length()
        val bytes = srcFile.inputStream().use { input ->
            withSFTPClient { it.open(dstPath, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)) }.use { remote ->
                CountingOutputStreamImpl(remote.RemoteFileOutputStream(), srcFileSize, onUploading).use { output ->
                    input.copyTo(output)
                }
            }
        }
        checkTransferSize(dstPath, srcFileSize, bytes)
        onUploading(bytes, bytes)
    }

    override fun read(src: String, output: OutputStream, onDownloading: (written: Long, total: Long) -> Unit) {
        val bytes = withSFTPClient { it.open(src, setOf(OpenMode.READ)) }.use { remote ->
            val attributes = remote.fetchAttributes()
            val size = if (attributes.has(FileAttributes.Flag.SIZE)) attributes.size else -1L
            remote.RemoteFileInputStream().use { input ->
                CountingOutputStreamImpl(output, size, onDownloading).use { output ->
                    input.copyTo(output).also { checkTransferSize(src, size, it) }
                }
            }
        }
        onDownloading(bytes, bytes)
    }

    override fun deleteFile(src: String) {
        log { "deleteFile: $src" }
        withSFTPClient { it.rm(src) }
    }

    override fun removeEmptyDirectory(src: String) {
        log { "removeDirectory: $src" }
        withSFTPClient { it.rmdir(src) }
    }

    override fun deleteRecursively(src: String) {
        withSFTPClient { client ->
            if (client.lstat(src).type != FileMode.Type.DIRECTORY) {
                client.rm(src)
                return@withSFTPClient
            }
            for (item in client.ls(src)) {
                check(item.name.isNotEmpty() && item.name != "." && item.name != ".." && '/' !in item.name)
                val child = "${src.trimEnd('/')}/${item.name}"
                if (item.isDirectory) deleteRecursively(child) else client.rm(child)
            }
            client.rmdir(src)
        }
    }

    override fun listFiles(src: String): DirChildrenParcelable {
        log { "listFiles: $src" }
        val files = ArrayList<FileParcelable>()
        val directories = ArrayList<FileParcelable>()
        val dirInfo = withSFTPClient { it.ls(src) }
        for (item in dirInfo) {
            if (item.isDirectory) {
                directories.add(FileParcelable(item.name, item.attributes.atime))
            } else {
                files.add(FileParcelable(item.name, item.attributes.atime))
            }
        }
        files.sortBy { it.name }
        directories.sortBy { it.name }
        return DirChildrenParcelable(files = files, directories = directories)
    }

    override fun walkFileTree(src: String): List<PathParcelable> {
        val pathParcelableList = mutableListOf<PathParcelable>()
        val srcFile = withSFTPClient { it.stat(src) }
        if (srcFile.type != FileMode.Type.DIRECTORY) {
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

    override fun exists(src: String): Boolean = withSFTPClient { client ->
        try {
            client.lstat(src)
            true
        } catch (e: SFTPException) {
            if (e.statusCode != StatusCode.NO_SUCH_FILE && e.statusCode != StatusCode.NO_SUCH_PATH) throw e
            false
        }
    }

    override suspend fun setRemote(context: Context, onSet: suspend (remote: String, extra: String) -> Unit) {
        val remote = pickDirectory(context, remotePath = { if (it.isEmpty()) "." else "./$it" })
        onSet(remote, entity.extra)
    }
}
