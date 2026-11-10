package com.xayah.core.network.client

import com.xayah.core.model.CloudType
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.WebDAVExtra
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDAVClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Server : AutoCloseable {
        private val server = MockWebServer()
        @Volatile var bytes = byteArrayOf()
        @Volatile var failTransfer = false
        @Volatile var chunked = false
        @Volatile var gzip = false
        @Volatile var redirect = MockResponse().setResponseCode(302)
        val url get() = server.url("/").toString()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.getHeader("Authorization") != Credentials.basic("test", "test") ->
                        MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"backup\"")
                    request.path == "/absent" -> MockResponse().setResponseCode(404)
                    request.path == "/forbidden" -> MockResponse().setResponseCode(403)
                    request.path == "/redirect/file" -> redirect
                    request.path == "/folder" && request.method == "PROPFIND" -> MockResponse().setResponseCode(207)
                        .setHeader("Content-Type", "application/xml")
                        .setBody("""
                            <d:multistatus xmlns:d="DAV:">
                            <d:response><d:href>/folder/%23name.apk</d:href><d:propstat><d:prop><d:resourcetype/></d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                            <d:response><d:href>/folder/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/>
                            </d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                            </d:multistatus>
                        """.trimIndent())
                    request.path == "/folder/%23name.apk" -> MockResponse().setBody("APK")
                    request.path == "/locked" && request.method in listOf("DELETE", "MOVE") ->
                        MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody("""
                            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/locked/data.tar</d:href>
                            <d:status>HTTP/1.1 ${if (failTransfer) "423 Locked" else "200 OK"}</d:status></d:response></d:multistatus>
                        """.trimIndent())
                    request.method == "PROPFIND" -> MockResponse().setResponseCode(207)
                        .setHeader("Content-Type", "application/xml")
                        .setBody("""
                            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href><d:propstat>
                            <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
                        """.trimIndent())
                    failTransfer -> MockResponse().setResponseCode(500)
                    request.method == "PUT" -> {
                        bytes = request.body.readByteArray()
                        MockResponse().setResponseCode(201)
                    }
                    gzip -> {
                        val compressed = Buffer()
                        GzipSink(compressed).buffer().use { it.write(bytes) }
                        MockResponse().setHeader("Content-Encoding", "gzip").setBody(compressed)
                    }
                    chunked -> MockResponse().setChunkedBody(Buffer().write(bytes), 4096)
                    else -> MockResponse().setBody(Buffer().write(bytes))
                }
            }
            server.start()
        }

        override fun close() = server.shutdown()
    }

    private fun client(server: Server) = WebDAVClientImpl(
        CloudEntity("test", CloudType.WEBDAV, server.url, "test", "test", "/backup", "", false),
        WebDAVExtra(insecure = false),
    )

    @Test
    fun redirectedDownloadDoesNotOfferAccountCredentialsToAnotherOrigin() {
        Server().use { server ->
            MockWebServer().use { storage ->
                server.redirect = MockResponse().setResponseCode(302).setHeader("Location", storage.url("/signed"))
                val client = client(server)
                client.connect()
                try {
                    val destination = temporary.newFolder()
                    storage.enqueue(MockResponse().setBody("data"))
                    client.download("/redirect/file", destination.path) { _, _ -> }
                    assertEquals("data", destination.resolve("file").readText())
                    assertNull(storage.takeRequest().getHeader("Authorization"))
                    storage.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"storage\""))
                    storage.enqueue(MockResponse().setBody("private"))
                    assertThrows(IOException::class.java) { client.download("/redirect/file", destination.path) { _, _ -> } }
                    assertEquals(2, storage.requestCount)
                } finally {
                    client.disconnect()
                }
            }
        }
    }

    @Test
    fun redirectsCannotTurnAnUploadIntoASuccessfulRead() {
        Server().use { server ->
            server.redirect = MockResponse().setResponseCode(302).setHeader("Location", server.url + "canonical")
            val client = client(server)
            client.connect()
            try {
                val source = temporary.newFile("file").apply { writeText("archive") }
                assertThrows(IOException::class.java) { client.upload(source.invariantSeparatorsPath, "/redirect") { _, _ -> } }
                MockWebServer().use { storage ->
                    server.redirect = MockResponse().setResponseCode(307).setHeader("Location", storage.url("/signed"))
                    storage.enqueue(MockResponse().setResponseCode(201))
                    client.upload(source.invariantSeparatorsPath, "/redirect") { _, _ -> }
                    val received = storage.takeRequest()
                    assertEquals("PUT", received.method)
                    assertArrayEquals(source.readBytes(), received.body.readByteArray())
                    assertNull(received.getHeader("Authorization"))
                }
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun authenticatedTransfersReportProgressAndPreserveBytes() {
        Server().use { server ->
            val client = client(server)
            client.connect()
            try {
                for (bytes in listOf(ByteArray(65_537) { it.toByte() }, byteArrayOf())) {
                    val source = temporary.newFile().apply { writeBytes(bytes) }
                    val uploaded = mutableListOf<Pair<Long, Long>>()
                    client.upload(source.path, "/backup") { written, total -> uploaded += written to total }
                    assertArrayEquals(bytes, server.bytes)
                    assertEquals(bytes.size.toLong() to bytes.size.toLong(), uploaded.last())
                    assertTrue(uploaded.zipWithNext().all { (previous, next) -> previous.first <= next.first })
                    if (bytes.isNotEmpty()) assertTrue(uploaded.any { it.first in 1 until bytes.size })
                    for (encoding in listOf("fixed", "chunked", "gzip")) {
                        server.chunked = encoding == "chunked"
                        server.gzip = encoding == "gzip"
                        val downloaded = ConcurrentLinkedQueue<Pair<Long, Long>>()
                        val destination = temporary.newFolder()
                        client.download("/backup/${source.name}", destination.path) { written, total -> downloaded += written to total }
                        assertArrayEquals(bytes, destination.resolve(source.name).readBytes())
                        assertTrue(downloaded.contains(bytes.size.toLong() to bytes.size.toLong()))
                    }
                }
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun resolvesChildrenByHrefAndKeepsReservedCharactersInFileNames() {
        Server().use { server ->
            val client = client(server)
            client.connect()
            try {
                assertEquals(listOf("#name.apk"), client.listFiles("/folder").files.map { it.name })
                val path = client.walkFileTree("/folder").single().pathString
                assertEquals("/folder/#name.apk", path)
                client.download(path, temporary.root.path) { _, _ -> }
                assertEquals("APK", temporary.root.resolve("#name.apk").readText())
                assertThrows(IOException::class.java) { client.listFiles("/unlisted") }
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun serverFailuresAreReportedToTheCaller() {
        Server().use { server ->
            val client = client(server)
            client.connect()
            try {
                client.deleteRecursively("/locked")
                server.failTransfer = true
                assertFalse(client.exists("/absent"))
                assertThrows(IOException::class.java) { client.exists("/forbidden") }
                val partial = assertThrows(IOException::class.java) { client.deleteRecursively("/locked") }
                assertTrue(partial.message.orEmpty().contains("/locked/data.tar (423)"))
                assertThrows(IOException::class.java) { client.renameTo("/locked", "/renamed") }
                client.removeEmptyDirectory("/forbidden")
                val source = temporary.newFile().apply { writeText("APK") }
                assertThrows(IOException::class.java) { client.upload(source.path, "/backup") { _, _ -> } }
                assertThrows(IOException::class.java) { client.download("/backup/app.apk", temporary.root.path) { _, _ -> } }
            } finally {
                client.disconnect()
            }
        }
    }
}
