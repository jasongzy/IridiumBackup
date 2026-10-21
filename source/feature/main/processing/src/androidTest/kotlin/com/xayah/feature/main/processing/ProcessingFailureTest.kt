package com.xayah.feature.main.processing

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.OperationState
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.AbstractProcessingServiceProxy
import com.xayah.core.ui.model.ProcessingDataCardItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class ProcessingFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val root = RemoteRootService(context)
    private val repository = TaskRepository(context, root, database.packageDao(), database.taskDao())
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

        fun setTaskId(id: Long) {
            _taskId.value = id
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
    fun initializationFailureIsAnInspectableTerminalState() = runBlocking {
        val model = create()
        model.initialize(autoStart = true)
        val state = withTimeout(5_000) { model.uiState.first { it.state == OperationState.ERROR } }
        assertTrue(state.failure.orEmpty().contains("Unable to bind"))
    }

    @Test
    fun processingFailureIsAnInspectableTerminalState() = runBlocking {
        val model = create()
        model.setTaskId(1)
        model.emitIntent(ProcessingUiIntent.Process)
        assertEquals(OperationState.ERROR, model.uiState.value.state)
        assertTrue(model.uiState.value.failure.orEmpty().contains("Unable to bind"))
    }

    @Test
    fun cancellingInitializationReleasesTheBindingWithoutReportingFailure() = runBlocking {
        val binding = BindingContext(context, acceptBinding = true)
        val model = create(binding)
        model.initialize(autoStart = true)
        withTimeout(5_000) { binding.bound.await() }
        model.emitIntent(ProcessingUiIntent.DestroyService)
        withContext(Dispatchers.Main) {
            assertEquals(1, binding.unbindCount)
            assertEquals(OperationState.IDLE, model.uiState.value.state)
            assertEquals(null, model.uiState.value.failure)
        }
    }

    @Test
    fun clearingAnOlderScreenPreservesTheCurrentRootErrorCallback() = runBlocking {
        create()
        create()
        val current = root.onFailure
        withContext(Dispatchers.Main) {
            stores[0].clear()
            assertSame(current, root.onFailure)
            stores[1].clear()
            assertNotSame(current, root.onFailure)
        }
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
