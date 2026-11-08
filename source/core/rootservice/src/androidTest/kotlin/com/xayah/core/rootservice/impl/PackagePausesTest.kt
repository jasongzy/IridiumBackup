package com.xayah.core.rootservice.impl

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.service.RemoteRootService
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePausesTest {
    @Test
    fun pauseHandlesReleaseTheirOwnerOnTheOriginalService() = runBlocking {
        val root = RemoteRootService(InstrumentationRegistry.getInstrumentation().targetContext)
        val field = root.javaClass.getDeclaredField("mService").apply { isAccessible = true }
        var owner: IBinder? = null
        var resumed: IBinder? = null
        var replyLost = false
        fun connection(original: Boolean): IRemoteRootService {
            val binder = Binder()
            return Proxy.newProxyInstance(IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java)) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "pausePackage" -> {
                        owner = args!![2] as IBinder
                        check(!replyLost)
                        null
                    }
                    "resumePackage" -> {
                        check(original)
                        resumed = args!![0] as IBinder
                        null
                    }
                    else -> error("Unexpected call: ${method.name}")
                }
            } as IRemoteRootService
        }
        field.set(root, connection(true))
        val pause = checkNotNull(root.pausePackage("com.example.target", 0))
        field.set(root, connection(false))
        pause.close()
        assertNotNull(owner)
        assertSame(owner, resumed)

        replyLost = true
        field.set(root, connection(true))
        assertNull(root.pausePackage("com.example.target", 0))
        assertSame(owner, resumed)
    }

    @Test
    fun cancelledPauseDoesNotRequestForceStopFallback() = runBlocking {
        val service = RemoteRootService(InstrumentationRegistry.getInstrumentation().targetContext)
        launch {
            cancel()
            val result = runCatching { service.pausePackage("com.example.target", 0) }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }.join()
    }

    @Test
    fun childStaysPausedUntilItsLastOwnerIsReleasedOnClose() {
        val child = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 30").start()
        val pauses = PackagePauses()
        try {
            val pid = child.inputStream.bufferedReader().use { it.readLine().toInt() }
            val first = Binder()
            val second = Binder()
            pauses.pause(first, listOf(pid))
            awaitStopped(pid, true)
            pauses.pause(second, listOf(pid))
            pauses.resume(first)
            awaitStopped(pid, true)
            pauses.close()
            awaitStopped(pid, false)
        } finally {
            pauses.close()
            child.destroy()
        }
    }

    private fun awaitStopped(pid: Int, expected: Boolean) {
        val status = File("/proc/$pid/status")
        val deadline = SystemClock.uptimeMillis() + 2000
        do {
            val state = status.useLines { lines -> lines.first { it.startsWith("State:") } }
            val stopped = state.substringAfter(':').trimStart().startsWith('T')
            if (stopped == expected) return
            if (SystemClock.uptimeMillis() >= deadline) assertEquals(expected, stopped)
            SystemClock.sleep(10)
        } while (true)
    }
}
