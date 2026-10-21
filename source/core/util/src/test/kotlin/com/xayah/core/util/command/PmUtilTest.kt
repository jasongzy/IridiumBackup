package com.xayah.core.util.command

import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PmUtilTest {
    private val apks = listOf("/apk/base.apk", "/apk/split.apk")
    private fun result(code: Int = 0, text: String = "Success") = ShellResult(code, emptyList(), listOf(text))

    @Test
    fun commitsOnlyAfterAllApksAreWritten() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val installed = installApkSession(apks, { result(text = "42") }) {
            commands += it
            result()
        }
        assertTrue(installed.isSuccess)
        assertEquals(listOf("install-write", "install-write", "install-commit"), commands.map { it.first() })
        assertEquals(listOf("install-write", "\"42\"", "\"base.apk\"", "\"/apk/base.apk\""), commands.first())
    }

    @Test
    fun creationFailureDoesNotUseAnEmptySession() = runBlocking {
        val installed = installApkSession(apks, { result(-1, "failed") }) { error("No session was created") }
        assertFalse(installed.isSuccess)
    }

    @Test
    fun writeAndCommitFailuresAbandonTheSession() = runBlocking {
        for (failure in listOf("install-write", "install-commit")) {
            val commands = mutableListOf<String>()
            val installed = installApkSession(apks, { result(text = "42") }) {
                commands += it.first()
                result(if (it.first() == failure) -1 else 0)
            }
            assertFalse(installed.isSuccess)
            val expected = if (failure == "install-write") listOf("install-write", "install-abandon")
            else listOf("install-write", "install-write", "install-commit", "install-abandon")
            assertEquals(expected, commands)
        }
    }

    @Test
    fun cancellationDuringCreationOrWritingStillAbandonsTheSession() = runBlocking {
        for (cancelDuringCreation in listOf(true, false)) {
            val commands = mutableListOf<String>()
            val job = launch {
                val caller = currentCoroutineContext()
                installApkSession(apks, {
                    if (cancelDuringCreation) caller.cancel()
                    result(text = "42")
                }) {
                    commands += it.first()
                    if (it.first() == "install-write") throw CancellationException()
                    assertTrue(currentCoroutineContext().isActive)
                    result()
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            val expected = if (cancelDuringCreation) listOf("install-abandon") else listOf("install-write", "install-abandon")
            assertEquals(expected, commands)
        }
    }
}
