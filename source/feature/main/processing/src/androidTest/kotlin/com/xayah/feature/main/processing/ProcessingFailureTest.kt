package com.xayah.feature.main.processing

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.SystemClock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.CloudType
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.MediaExtraInfo
import com.xayah.core.model.database.MediaIndexInfo
import com.xayah.core.model.database.MediaInfo
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.toPackageEntity
import com.xayah.core.network.client.CloudClient
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.AbstractProcessingServiceProxy
import com.xayah.core.service.medium.restore.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.medium.restore.ProcessingServiceProxyLocalImpl
import com.xayah.core.service.util.PackagesRestoreUtil
import com.xayah.core.ui.model.ProcessingDataCardItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.PathUtil
import com.xayah.feature.main.processing.medium.restore.RestoreViewModelImpl
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class ProcessingFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val root = RemoteRootService(context)
    private val repository = TaskRepository(database.taskDao())
    private val stores = mutableListOf<ViewModelStore>()

    private class BindingContext(context: Context, val acceptBinding: Boolean = false) : ContextWrapper(context) {
        val bound = CompletableDeferred<Unit>()
        var unbindCount = 0

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bound.complete(Unit)
            return acceptBinding
        }

        override fun unbindService(connection: ServiceConnection) {
            unbindCount++
        }
    }

    private class TestViewModel(context: Context, root: RemoteRootService, repository: TaskRepository) : AbstractPackagesProcessingViewModel(
        context, root, repository, proxy(context), proxy(context),
    ) {
        override val _dataItems = flowOf(emptyList<ProcessingDataCardItem>())

        suspend fun startTask() {
            submitSetup {}
            initialize(autoStart = true)
        }

        suspend fun submitSetup(onReady: suspend () -> Unit) = finishSetup(uiState.value, onReady)

        companion object {
            private fun proxy(bindingContext: Context) = object : AbstractProcessingServiceProxy() {
                override val context = bindingContext
                override val intent = Intent("test.processing")
            }
        }
    }

    private fun create(context: Context = BindingContext(this.context)): TestViewModel =
        TestViewModel(context, root, repository).also { model ->
            stores += ViewModelStore().apply { put("processing", model) }
        }

    @After
    fun close() = runBlocking {
        withContext(Dispatchers.Main) { stores.forEach { it.clear() } }
        database.close()
    }

    @Test
    fun cloudRestoreReportsTheFailedArchiveAndReadsTheNextOne() = runBlocking {
        val cloud = CloudRepository(context, root, database.cloudDao())
        val packages = PackageRepository(context, root, cloud, database.packageDao(), PathUtil(context))
        val restore = PackagesRestoreUtil(context, root, database.taskDao(), packages, cloud)
        val app = PackageRestoreConfig(
            packageName = "test.app", userId = 0, createdAt = 1, compressionType = CompressionType.TAR,
            label = "Test", versionName = "1", versionCode = 1, isSystemApp = false, hasKeystore = false,
            permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
            dataStats = PackageDataStats(), displayStats = PackageDataStats(),
        ).toPackageEntity("cloud", "/backup")
        val task = TaskDetailPackageEntity(id = 1, taskId = 1, packageEntity = app)
        val client = Proxy.newProxyInstance(CloudClient::class.java.classLoader, arrayOf(CloudClient::class.java)) { _, method, args ->
            if (method.name != "download") throw SecurityException("Listing not permitted")
            val path = args!![0] as String
            check(!path.endsWith("/user.tar")) { "Archive missing: $path" }
            File(args[1] as String, "user_de.tar").writeText("data")
        } as CloudClient
        restore.download(client, app, task, DataType.PACKAGE_USER, "/backup") { error("Missing archive reached restore") }
        assertEquals(OperationState.ERROR, task.userInfo.state)
        assertTrue(task.userInfo.log.contains("Archive missing: /backup/user.tar"))
        var contents: String? = null
        restore.download(client, app, task, DataType.PACKAGE_USER_DE, "/backup") { dir ->
            contents = File(dir, "user_de.tar").readText()
            task.userDeInfo.state = OperationState.DONE
        }
        assertEquals("data", contents)
        assertEquals(OperationState.DONE, task.userDeInfo.state)
    }

    @Test
    fun screenOffAppliesOnlyToThePageVisibleAtTheDeadline() = runBlocking(Dispatchers.Main) {
        fun page() = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val foreground = page()
        val background = page()
        val shown = CompletableDeferred<Unit>()
        val turnedOff = CompletableDeferred<Unit>()
        var backgroundMessages = 0
        var backgroundRequests = 0
        val deadline = SystemClock.elapsedRealtime() + 1_000
        try {
            launch { foreground.lifecycle.countDownToScreenOff(deadline, {}) { turnedOff.complete(Unit) } }
            launch {
                background.lifecycle.countDownToScreenOff(deadline, { backgroundMessages++; shown.complete(Unit) }) { backgroundRequests++ }
            }
            withTimeout(5_000) {
                shown.await()
                background.lifecycle.currentState = Lifecycle.State.STARTED
                turnedOff.await()
                background.lifecycle.currentState = Lifecycle.State.RESUMED
                yield()
            }
            assertEquals(1, backgroundMessages)
            assertEquals(0, backgroundRequests)
        } finally {
            foreground.lifecycle.currentState = Lifecycle.State.DESTROYED
            background.lifecycle.currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test
    fun fileRestorePreviewsTheSelectedDirectoryEvenAfterTheAccountPathChanges() = runBlocking {
        val cloud = CloudRepository(context, root, database.cloudDao())
        val account = CloudEntity("server", CloudType.FTP, "localhost", "", "", "/new", "", false)
        cloud.add(account)
        val files = MediaRepository(context, root, cloud, database.mediaDao(), PathUtil(context))
        val original = MediaEntity(
            0, MediaIndexInfo(OpType.RESTORE, "docs", CompressionType.TAR, 1, account.name, "/original"),
            MediaInfo("/docs", 42, 42), MediaExtraInfo(0, false, true, true),
        )
        files.upsert(listOf(original, original.copy(indexInfo = original.indexInfo.copy(name = "other", backupDir = account.remote))))
        val model = RestoreViewModelImpl(
            context, root, repository, files, cloud, ProcessingServiceProxyLocalImpl(), ProcessingServiceProxyCloudImpl(),
            SavedStateHandle(mapOf(MainRoutes.ARG_ACCOUNT_NAME to account.name, MainRoutes.ARG_ACCOUNT_REMOTE to "/original")),
        )
        stores += ViewModelStore().apply { put("restore", model) }
        model.emitIntent(LoadRestoreSource)
        assertEquals(listOf("docs"), model.medium.value?.map { it.name })
        assertEquals("/original", model.uiState.value.backupDir)
        cloud.delete(account.name)
        model.emitIntent(LoadRestoreSource)
        assertTrue(model.medium.value!!.isEmpty())
    }

    @Test
    fun losingConfirmationDoesNotStartALocalTask() = runBlocking {
        val binding = BindingContext(context)
        val model = create(binding)
        model.initialize(autoStart = true)
        val state = withTimeout(5_000) { model.uiState.first { it.state == OperationState.ERROR } }
        assertEquals(context.getString(com.xayah.core.service.R.string.package_request_expired), state.failure)
        assertFalse(binding.bound.isCompleted)
    }

    @Test
    fun initializationFailureIsAnInspectableTerminalState() = runBlocking {
        val binding = BindingContext(context)
        val model = create(binding)
        model.startTask()
        val state = withTimeout(5_000) { model.uiState.first { it.state == OperationState.ERROR } }
        assertEquals(context.getString(com.xayah.core.service.R.string.processing_service_bind_failed), state.failure)
        assertEquals(1, binding.unbindCount)
    }

    @Test
    fun serviceCancellationIsReportedWithoutCancellingTheScreen() = runBlocking {
        val message = context.getString(com.xayah.core.service.R.string.processing_service_time_limit)
        var unbound = false
        val binding = object : ContextWrapper(context) {
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                throw CancellationException(message)
            }

            override fun unbindService(connection: ServiceConnection) {
                unbound = true
            }
        }
        val model = create(binding)
        model.startTask()
        val state = withTimeout(5_000) { model.uiState.first { it.state == OperationState.ERROR } }
        assertEquals(message, state.failure)
        assertTrue(unbound)
        model.emitIntent(ProcessingUiIntent.Exit)
        assertEquals(AbstractProcessingViewModel.ExitState.COMPLETE, model.exitState.value)
    }

    @Test
    fun aSecondTaskCannotBindOrReleaseTheRunningTask() = runBlocking {
        val firstBinding = BindingContext(context, acceptBinding = true)
        val first = create(firstBinding)
        first.startTask()
        withTimeout(5_000) { firstBinding.bound.await() }

        val secondBinding = BindingContext(context)
        val second = create(secondBinding)
        second.startTask()
        val failure = withTimeout(5_000) { second.uiState.first { it.state == OperationState.ERROR } }
        assertEquals(context.getString(com.xayah.core.service.R.string.processing_task_busy), failure.failure)
        second.emitIntent(ProcessingUiIntent.Exit)
        assertFalse(secondBinding.bound.isCompleted)
        assertEquals(0, firstBinding.unbindCount)

        first.emitIntent(ProcessingUiIntent.Exit)
        assertEquals(1, firstBinding.unbindCount)
        create(secondBinding).startTask()
        withTimeout(5_000) { secondBinding.bound.await() }
    }

    @Test
    fun cancellingInitializationReleasesTheBindingWithoutReportingFailure() = runBlocking {
        val binding = BindingContext(context, acceptBinding = true)
        val model = create(binding)
        model.startTask()
        withTimeout(5_000) { binding.bound.await() }
        model.emitIntent(ProcessingUiIntent.Exit)
        assertEquals(AbstractProcessingViewModel.ExitState.COMPLETE, model.exitState.value)
        withContext(Dispatchers.Main) {
            assertEquals(1, binding.unbindCount)
            assertEquals(OperationState.IDLE, model.uiState.value.state)
            assertEquals(null, model.uiState.value.failure)
        }
    }

    @Test
    fun closingAProcessingScreenRestoresThePreviousRootErrorHandler() = runBlocking {
        val messages = mutableListOf<String>()
        root.observeFailures { messages += it.message.orEmpty() }.use {
            create()
            withContext(Dispatchers.Main) { stores.single().clear() }
            root.onFailure(IOException("root unavailable"))
        }
        root.onFailure(IOException("unused"))
        assertEquals(listOf("root unavailable"), messages)
    }

    @Test
    fun setupSubmitsOnceDuringPreparationAndAfterNavigation() = runBlocking {
        withTimeout(5_000) {
            val model = create()
            val preparing = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            var submissions = 0
            val first = launch {
                model.submitSetup {
                    preparing.complete(Unit)
                    ready.await()
                    submissions++
                }
            }
            preparing.await()
            assertTrue(model.isStarting.value)
            model.submitSetup { submissions++ }
            ready.complete(Unit)
            first.join()
            model.submitSetup { submissions++ }
            assertEquals(1, submissions)
        }
    }

    @Test
    fun failedSetupAllowsAnotherSubmission() = runBlocking {
        val model = create()
        model.submitSetup { throw IOException("Setup failed") }
        assertFalse(model.isStarting.value)
        var submissions = 0
        model.submitSetup { submissions++ }
        assertEquals(1, submissions)
        assertTrue(model.isStarting.value)
    }
}
