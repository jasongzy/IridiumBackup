package com.xayah.core.util.archive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArchiveStepTest {
    @Test
    fun cancellationFinishesTheActiveStepAndDoesNotStartTheNext() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val extracted = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val task = launch {
                finishArchiveStep {
                    started.complete(Unit)
                    extracted.await()
                    events += "permissions restored"
                }
                events += "next archive"
            }
            started.await()
            task.cancel()
            assertFalse(task.isCompleted)
            extracted.complete(Unit)
            task.join()
            assertEquals(listOf("permissions restored"), events)
        }
    }
}
