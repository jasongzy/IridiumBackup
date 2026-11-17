package com.xayah.core.data.repository

import android.os.Binder
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.service.RemoteRootService
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTransferTest {
    @Test
    fun checksumCancellationDoesNotProduceAResultOrStageAnArchive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val started = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        var completed = false
        try {
            val repo = CloudRepository(context, RemoteRootService(context), database.cloudDao())
            val client = Proxy.newProxyInstance(CloudClient::class.java.classLoader, arrayOf(CloudClient::class.java)) { _, method, args ->
                check(method.name == "read")
                started.complete(Unit)
                check(resume.await(5, TimeUnit.SECONDS))
                (args!![1] as java.io.OutputStream).write(byteArrayOf(1, 2, 3))
                @Suppress("UNCHECKED_CAST")
                (args[2] as (Long, Long) -> Unit)(3, 6)
            } as CloudClient
            val job = launch {
                repo.inspectFile(client, "/backup/apk.tar") { _, _ -> }
                completed = true
            }
            withTimeout(5_000) { started.await() }
            job.cancel()
            resume.countDown()
            job.join()
            assertFalse(completed)
        } finally {
            resume.countDown()
            database.close()
        }
    }

    @Test
    fun aStagedFileCleanupFailureDoesNotUndoACompletedUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val workspace = File(context.cacheDir, "cloud-cleanup-test").apply { mkdirs() }
        try {
            val staged = File(workspace, "config.json").apply { writeText("backup") }
            val binder = Binder()
            val service = Proxy.newProxyInstance(IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java)) { _, method, _ ->
                when (method.name) {
                    "asBinder" -> binder
                    "removeEntry" -> false
                    else -> error("Unexpected root operation: ${method.name}")
                }
            }
            val root = RemoteRootService(context).apply {
                javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, service)
            }
            var uploaded: String? = null
            val client = Proxy.newProxyInstance(CloudClient::class.java.classLoader, arrayOf(CloudClient::class.java)) { _, method, args ->
                check(method.name == "upload")
                uploaded = File(args!![0] as String).readText()
                null
            } as CloudClient
            val result = CloudRepository(context, root, database.cloudDao()).upload(client, staged.path, "/backup")
            assertTrue(result.isSuccess)
            assertEquals("backup", uploaded)
            assertTrue(staged.exists())
        } finally {
            database.close()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun cancellationStopsTheTransferBeforeExtraction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val started = CompletableDeferred<File>()
        val resume = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val extracted = AtomicBoolean(false)
        try {
            val repo = CloudRepository(context, RemoteRootService(context), db.cloudDao())
            val client = Proxy.newProxyInstance(CloudClient::class.java.classLoader, arrayOf(CloudClient::class.java)) { _, method, args ->
                check(method.name == "download")
                started.complete(File(args!![1] as String))
                check(resume.await(5, TimeUnit.SECONDS))
                @Suppress("UNCHECKED_CAST")
                (args[2] as (Long, Long) -> Unit)(1, 2)
                finished.set(true)
            } as CloudClient
            val job = launch { repo.download(client, "app/data.tar") { extracted.set(true) } }
            val directory = withTimeout(5_000) { started.await() }
            job.cancel()
            resume.countDown()
            job.join()
            assertFalse(finished.get())
            assertFalse(extracted.get())
            assertFalse(directory.exists())
        } finally {
            resume.countDown()
            db.close()
        }
    }

    @Test
    fun overlappingDownloadsOfTheSameNameSurviveInnerCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = CloudRepository(context, RemoteRootService(context), database.cloudDao())
            val client = Proxy.newProxyInstance(CloudClient::class.java.classLoader, arrayOf(CloudClient::class.java)) { _, method, args ->
                check(method.name == "download")
                val path = args!![0] as String
                File(args[1] as String, "config.json").writeText(if (path == "valid/config.json") "42" else path)
                @Suppress("UNCHECKED_CAST")
                val progress = args[2] as (Long, Long) -> Unit
                progress(2, -1)
                progress(5, -1)
            } as CloudClient
            lateinit var outer: File
            val updates = mutableListOf<Long>()
            val result = repo.download(client, "a/config.json", onDownloading = { bytes, _ -> updates += bytes }) { path ->
                assertEquals(5L, updates.last())
                outer = File(path)
                lateinit var inner: File
                val cancelled = runCatching {
                    repo.download(client, "b/config.json") {
                        inner = File(it)
                        assertEquals("b/config.json", inner.readText())
                        throw CancellationException()
                    }
                }
                assertTrue(cancelled.exceptionOrNull() is CancellationException)
                assertFalse(inner.parentFile!!.exists())
                assertEquals("a/config.json", outer.readText())
            }
            assertTrue(result.isSuccess)
            assertFalse(outer.parentFile!!.exists())
            assertEquals(42, repo.readBackupConfig(client, "valid/config.json", Int::class.javaObjectType))
            assertTrue(runCatching { repo.readBackupConfig(client, "invalid/config.json", Int::class.javaObjectType) }.isFailure)
        } finally {
            database.close()
        }
    }
}
