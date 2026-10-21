package com.xayah.core.network.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CountingStreamTest {
    @Test
    fun reportsBytesReadInsteadOfRequestedBufferSize() {
        val progress = AtomicLong(-1)
        val reported = CountDownLatch(1)
        CountingInputStreamImpl(byteArrayOf(1, 2, 3).inputStream(), 3) { read, _ ->
            progress.set(read)
            if (read > 0) reported.countDown()
        }.use { stream ->
            val buffer = ByteArray(8192)
            assertEquals(3, stream.read(buffer))
            assertEquals(-1, stream.read(buffer))
            assertTrue(reported.await(3, TimeUnit.SECONDS))
            assertEquals(3, progress.get())
            assertEquals(3, stream.byteCount)
        }
    }

    @Test
    fun closesTheProgressTimerEvenWhenOutputCloseFails() {
        val reported = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) {}
            override fun close() { throw IOException("close failed") }
        }
        val stream = CountingOutputStreamImpl(output, 1) { _, _ -> reported.countDown() }
        assertTrue(reported.await(3, TimeUnit.SECONDS))
        assertThrows(IOException::class.java) { stream.close() }
        val timer = CountingOutputStreamImpl::class.java.getDeclaredField("timer").apply { isAccessible = true }.get(stream) as java.util.Timer
        assertThrows(IllegalStateException::class.java) { timer.schedule(object : java.util.TimerTask() { override fun run() {} }, 0) }
    }
}
