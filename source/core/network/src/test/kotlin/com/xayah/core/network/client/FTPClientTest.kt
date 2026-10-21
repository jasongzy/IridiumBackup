package com.xayah.core.network.client

import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.FTPExtra
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPListParseEngine
import org.apache.commons.net.ftp.parser.UnixFTPEntryParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.security.auth.login.LoginException

class FTPClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Server(val accepted: Boolean) : FTPClient() {
        var bytes = byteArrayOf(1, 2, 3)
        lateinit var uploadStream: InputStream
        lateinit var downloadStream: OutputStream

        override fun storeFile(remote: String, local: InputStream): Boolean {
            uploadStream = local
            bytes = local.readBytes()
            return accepted
        }

        override fun retrieveFile(remote: String, local: OutputStream): Boolean {
            downloadStream = local
            local.write(bytes)
            return accepted
        }
    }

    private class DirectoryServer : FTPClient() {
        val directories = mutableSetOf("/", "/home", "/backup")
        var workingDirectory = "/home"
        var deniedDirectory: String? = null

        private fun resolve(path: String) = if (path.startsWith('/')) path else "${workingDirectory.trimEnd('/')}/$path"

        override fun printWorkingDirectory(): String = workingDirectory

        override fun changeWorkingDirectory(pathname: String): Boolean {
            if (pathname.isEmpty()) return false
            val path = resolve(pathname)
            if (path !in directories || path == deniedDirectory) return false
            workingDirectory = path
            return true
        }

        override fun makeDirectory(pathname: String): Boolean =
            pathname.isNotEmpty() && directories.add(resolve(pathname))
    }

    private class ListingServer : FTPClient() {
        var denied = false
        val deletedFiles = mutableListOf<String>()
        val deletedDirectories = mutableListOf<String>()

        override fun getReplyCode() = if (denied) 550 else 226

        override fun mlistFile(pathname: String) = FTPFile().apply {
            name = pathname.substringAfterLast('/')
            type = FTPFile.DIRECTORY_TYPE
        }

        override fun listFiles(pathname: String): Array<FTPFile> {
            if (denied) return emptyArray()
            val children = when (pathname) {
                "/backup" -> """
                    -rw-r--r-- 1 user group 3 Jan 01 2026 .hidden.tar
                    drwxr-xr-x 1 user group 0 Jan 01 2026 child
                    lrwxrwxrwx 1 user group 9 Jan 01 2026 external -> /external
                """.trimIndent()
                "/backup/child" -> "-rw-r--r-- 1 user group 3 Jan 01 2026 data.tar"
                else -> error("Traversal escaped the listed directories: $pathname")
            }
            val listing = "drwxr-xr-x 1 user group 0 Jan 01 2026 .\n" +
                "drwxr-xr-x 1 user group 0 Jan 01 2026 ..\n" + children
            return FTPListParseEngine(UnixFTPEntryParser()).apply {
                listing.byteInputStream().use { readServerList(it, "UTF-8") }
            }.files
        }

        override fun deleteFile(pathname: String) = deletedFiles.add(pathname)

        override fun removeDirectory(pathname: String) = deletedDirectories.add(pathname)
    }

    private fun client(server: FTPClient): FTPClientImpl {
        val entity = CloudEntity("test", CloudType.FTP, "localhost", "test", "test", "/backup", "", false)
        return FTPClientImpl(entity, FTPExtra(21)).also {
            FTPClientImpl::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, server)
        }
    }

    @Test
    fun rejectsServerFailureAfterUploadingBytesAndClosesTheStream() {
        val source = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val server = Server(accepted = false)
        assertThrows(IOException::class.java) { client(server).upload(source.path, "/backup") { _, _ -> } }
        assertThrows(IOException::class.java) { server.uploadStream.read() }
    }

    @Test
    fun rejectsIncompleteDownloadAndClosesTheStream() {
        val server = Server(accepted = false)
        assertThrows(IOException::class.java) { client(server).download("/backup/app.tar", temporary.root.path) { _, _ -> } }
        assertThrows(IOException::class.java) { server.downloadStream.write(0) }
    }

    @Test
    fun roundTripsSuccessfulTransfersIncludingEmptyFiles() {
        val server = Server(accepted = true)
        val client = client(server)
        val destination = temporary.newFolder()
        for (bytes in listOf(byteArrayOf(1, 2, 3), byteArrayOf())) {
            val source = temporary.newFile().apply { writeBytes(bytes) }
            client.upload(source.path, "/backup") { _, _ -> }
            client.download("/backup/${source.name}", destination.path) { _, _ -> }
            assertArrayEquals(bytes, destination.resolve(source.name).readBytes())
        }
    }

    @Test
    fun createsAbsoluteAndRelativeDirectoriesWithoutChangingWorkingDirectory() {
        val server = DirectoryServer()
        val client = client(server)
        client.mkdirRecursively("/backup//apps/example/")
        client.mkdirRecursively("local/apps")
        client.mkdirRecursively("local/apps")
        assertEquals(
            setOf("/", "/home", "/backup", "/backup/apps", "/backup/apps/example", "/home/local", "/home/local/apps"),
            server.directories,
        )
        assertEquals("/home", server.workingDirectory)
    }

    @Test
    fun stopsWhenCreatedDirectoryCannotBeEnteredAndRestoresWorkingDirectory() {
        val server = DirectoryServer().apply { deniedDirectory = "/home/backup/apps" }
        assertThrows(IOException::class.java) { client(server).mkdirRecursively("backup/apps/example") }
        assertFalse(server.directories.any { it.endsWith("/example") })
        assertEquals("/home", server.workingDirectory)
    }

    @Test
    fun traversesOnlyChildrenAndDeletesLinksAsFiles() {
        val server = ListingServer()
        val client = client(server)
        assertEquals(listOf("child"), client.listFiles("/backup").directories.map { it.name })
        val files = setOf("/backup/.hidden.tar", "/backup/external", "/backup/child/data.tar")
        assertEquals(files, client.walkFileTree("/backup").map { it.pathString }.toSet())
        client.deleteRecursively("/backup")
        assertEquals(files, server.deletedFiles.toSet())
        assertEquals(listOf("/backup/child", "/backup"), server.deletedDirectories)
    }

    @Test
    fun rejectsFailedListingsInsteadOfTreatingThemAsEmptyDirectories() {
        val server = ListingServer().apply { denied = true }
        val client = client(server)
        assertThrows(IOException::class.java) { client.listFiles("/backup") }
        assertThrows(IOException::class.java) { client.deleteRecursively("/backup") }
        assertEquals(emptyList<String>(), server.deletedFiles)
        assertEquals(emptyList<String>(), server.deletedDirectories)
    }

    @Test
    fun rejectedLoginClosesConnectionAndKeepsCredentialsOutOfError() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3_000
            val executor = Executors.newSingleThreadExecutor()
            val exchange = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { output.write("$text\r\n"); output.flush() }
                    reply("220 Test FTP")
                    assertEquals("FEAT", input.readLine())
                    reply("502 Not implemented")
                    assertEquals("USER test-user", input.readLine())
                    reply("331 Password required")
                    assertEquals("PASS test-password", input.readLine())
                    reply("530 Login failed")
                    assertEquals(null, input.readLine())
                }
            }
            try {
                val entity = CloudEntity("test", CloudType.FTP, server.inetAddress.hostAddress!!, "test-user", "test-password", "/backup", "", false)
                val error = assertThrows(LoginException::class.java) { FTPClientImpl(entity, FTPExtra(server.localPort)).connect() }
                assertFalse(error.message.orEmpty().contains(entity.pass))
                assertFalse(error.message.orEmpty().contains(entity.user))
                exchange.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
