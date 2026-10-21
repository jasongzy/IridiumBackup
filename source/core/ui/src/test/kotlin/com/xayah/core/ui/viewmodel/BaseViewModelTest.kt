package com.xayah.core.ui.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseViewModelTest {
    private data class State(val search: String = "", val completed: Int = 0) : UiState

    private class Model : BaseViewModel<State, UiIntent, IndexUiEffect>(State()) {
        fun update(transform: (State) -> State) = updateState(transform)
    }

    @Test
    fun progressPreservesConcurrentSearchChanges() {
        val model = Model()
        val snapshotRead = CountDownLatch(1)
        val continueUpdate = CountDownLatch(1)
        val firstAttempt = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val progress = executor.submit {
                model.update {
                    if (firstAttempt.getAndSet(false)) {
                        snapshotRead.countDown()
                        check(continueUpdate.await(5, TimeUnit.SECONDS))
                    }
                    it.copy(completed = 1)
                }
            }
            assertTrue(snapshotRead.await(5, TimeUnit.SECONDS))
            model.update { it.copy(search = "example") }
            continueUpdate.countDown()
            progress.get(5, TimeUnit.SECONDS)
            assertEquals(State(search = "example", completed = 1), model.uiState.value)
        } finally {
            continueUpdate.countDown()
            executor.shutdownNow()
        }
    }
}
