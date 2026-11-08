package com.xayah.core.network.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class CountingStreamTest {
    @Test
    fun reportsBytesReadInsteadOfRequestedBufferSize() {
        val progress = mutableListOf<Long>()
        CountingInputStreamImpl(byteArrayOf(1, 2, 3).inputStream(), 3) { read, _ -> progress += read }.use { stream ->
            val buffer = ByteArray(8192)
            assertEquals(3, stream.read(buffer))
            assertEquals(-1, stream.read(buffer))
        }
        assertEquals(listOf(3L), progress)
    }

    @Test
    fun cancellationFromProgressReachesTheWriter() {
        val output = ByteArrayOutputStream()
        CountingOutputStreamImpl(output, 3) { written, _ ->
            assertEquals(3L, written)
            throw CancellationException()
        }.use { stream ->
            assertThrows(CancellationException::class.java) { stream.write(byteArrayOf(1, 2, 3)) }
        }
        assertEquals(3, output.size())
    }
}
