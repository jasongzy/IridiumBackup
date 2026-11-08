package com.xayah.core.network.io

import org.apache.commons.io.input.CountingInputStream
import java.io.InputStream

class CountingInputStreamImpl(
    `in`: InputStream,
    private val fileSize: Long,
    private val onProgress: (read: Long, total: Long) -> Unit
) : CountingInputStream(`in`) {
    override fun afterRead(n: Int) {
        super.afterRead(n)
        if (n > 0) onProgress(byteCount, fileSize)
    }
}
