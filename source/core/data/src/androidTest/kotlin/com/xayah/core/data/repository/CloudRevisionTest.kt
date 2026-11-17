package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.BackupManifest
import com.xayah.core.model.BackupManifestFile
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.model.CloudType
import com.xayah.core.model.CompressionType
import com.xayah.core.model.ConfigsPackageRestoreName
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.WebDAVExtra
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRevisionTest {
    @Test
    fun scanningVerificationAndNotePublicationShareTheRemoteManifest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = RemoteRootService(context)
        val clouds = CloudRepository(context, root, database.cloudDao())
        val repository = AppBackupRepository(
            context, database, PackageRepository(context, root, clouds, database.packageDao(), PathUtil(context)),
            AppIconRepository(context, root), XposedModuleDetector(context, root), root, clouds,
        )
        val files = ConcurrentHashMap<String, ByteArray>()
        var failRead = false
        var failMove = false
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = URI(request.path!!).path.trimEnd('/')
                fun exists(target: String) = files.containsKey(target) || files.keys.any { it.startsWith("$target/") }
                return when (request.method) {
                    "HEAD" -> MockResponse().setResponseCode(if (exists(path)) 200 else 404)
                    "GET" -> if (failRead) MockResponse().setResponseCode(503) else files[path]?.let {
                        MockResponse().setChunkedBody(Buffer().write(it), 8192)
                    } ?: MockResponse().setResponseCode(404)
                    "PROPFIND" -> {
                        if (!exists(path)) return MockResponse().setResponseCode(404)
                        val children = files.keys.filter { it.startsWith("$path/") }
                            .map { "$path/${it.removePrefix("$path/").substringBefore('/')}" }.toSet()
                        val entries = (setOf(path) + children).joinToString("") { entry ->
                            val type = if (files.containsKey(entry)) "" else "<d:collection/>"
                            "<d:response><d:href>${entry.ifEmpty { "/" }}</d:href><d:propstat><d:prop><d:resourcetype>$type</d:resourcetype>" +
                                "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        }
                        MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                            .setBody("<d:multistatus xmlns:d=\"DAV:\">$entries</d:multistatus>")
                    }
                    "PUT" -> {
                        files[path] = request.body.readByteArray()
                        MockResponse().setResponseCode(201)
                    }
                    "MOVE" -> if (failMove) MockResponse().setResponseCode(403) else {
                        check(request.getHeader("Overwrite") == "T")
                        files[URI(request.getHeader("Destination")!!).path] = files.remove(path)!!
                        MockResponse().setResponseCode(204)
                    }
                    "DELETE" -> {
                        files.remove(path)
                        MockResponse().setResponseCode(204)
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        try {
            server.start()
            clouds.add(CloudEntity("test", CloudType.WEBDAV, server.url("/").toString(), "", "", "/backup",
                root.gsonUtil.toJson(WebDAVExtra(false)), false))
            val config = PackageRestoreConfig(
                packageName = "com.example.cloud", userId = 0, createdAt = 1234, compressionType = CompressionType.TAR,
                label = "Cloud", versionName = "1.0", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates.fromArchiveMask(1),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            )
            val dir = "/backup/apps/${config.packageName}/user_0@1234"
            val apk = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            files["$dir/apk.tar"] = apk
            files["$dir/$ConfigsPackageRestoreName"] = root.gsonUtil.toJson(config).toByteArray()
            val manifestPath = PathUtil.getBackupManifestDst(dir)
            val manifest = BackupManifest(
                packageName = config.packageName, userId = 0, createdAt = 1234, versionName = "1.0", versionCode = 1,
                contentMask = 1, note = "remote note", files = files.entries.map { (path, bytes) ->
                    BackupManifestFile(path.substringAfterLast('/'), bytes.size.toLong(),
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                },
            )
            files[manifestPath] = root.gsonUtil.toJson(manifest).toByteArray()
            repository.loadCloudIndex("test", "/backup") { _, _, _ -> }
            val dao = database.appBackupDao()
            val revision = dao.getRevisions("test:/backup").single()
            assertEquals("remote note", revision.note)
            val progress = mutableListOf<Long>()
            assertEquals(BackupVerificationStatus.VALID, repository.inspectRevision(revision) { name, bytes, _ ->
                if (name == "apk.tar") progress += bytes
            }.status)
            assertEquals(apk.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            assertTrue(repository.updateRevisionNote(revision, " edited "))
            assertEquals("edited", dao.getRevisions("test:/backup").single().note)
            assertEquals("edited", root.gsonUtil.fromJson<BackupManifest>(files.getValue(manifestPath).toString(Charsets.UTF_8), BackupManifest::class.java).note)
            failMove = true
            assertTrue(runCatching { repository.updateRevisionNote(revision, "not saved") }.isFailure)
            assertEquals("edited", dao.getRevisions("test:/backup").single().note)
            assertEquals(3, files.size)
            failMove = false
            files[manifestPath] = root.gsonUtil.toJson(manifest.copy(note = "another device")).toByteArray()
            repository.loadCloudIndex("test", "/backup") { _, _, _ -> }
            assertEquals(revision.id, dao.getRevisions("test:/backup").single().id)
            assertEquals("another device", dao.getRevisions("test:/backup").single().note)
            val scanFailure = withTimeout(5000) {
                runCatching {
                    repository.loadCloudIndex("test", "/backup") { _, _, _ ->
                        assertTrue(repository.updateRevisionNote(revision, "during scan"))
                    }
                }.exceptionOrNull()
            }
            assertTrue(scanFailure is IllegalStateException)
            assertEquals("during scan", dao.getRevisions("test:/backup").single().note)
            files["$dir/apk.tar"] = apk.copyOf().apply { this[0] = 42 }
            val damaged = repository.inspectRevision(revision)
            assertEquals(AppBackupRepository.VerificationIssueType.FILE_CHECKSUM_MISMATCH, damaged.issues.single().type)
            failRead = true
            assertTrue(runCatching { repository.inspectRevision(revision) }.isFailure)
            assertEquals(damaged, repository.verificationResults.value[revision.id])
            failRead = false
            files.remove("$dir/apk.tar")
            assertEquals(AppBackupRepository.VerificationIssueType.FILE_MISSING, repository.inspectRevision(revision).issues.single().type)
            files.clear()
            files["/backup/apps/keep-directory"] = byteArrayOf()
            repository.loadCloudIndex("test", "/backup") { _, _, _ -> }
            assertTrue(dao.getRevisions("test:/backup").isEmpty())
            assertFalse(repository.verificationResults.value.containsKey(revision.id))
        } finally {
            database.close()
            server.shutdown()
        }
    }
}
