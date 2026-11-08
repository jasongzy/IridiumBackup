package com.xayah.core.network.io

import org.apache.commons.io.output.CountingOutputStream
import java.io.OutputStream

class CountingOutputStreamImpl(
    `out`: OutputStream,
    private val fileSize: Long,
    private val onProgress: (written: Long, total: Long) -> Unit
) : CountingOutputStream(`out`) {
    override fun afterWrite(n: Int) {
        if (n > 0) onProgress(byteCount, fileSize)
    }
}
