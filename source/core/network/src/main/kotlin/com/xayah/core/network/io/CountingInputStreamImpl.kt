package com.xayah.core.network.io

import org.apache.commons.io.input.CountingInputStream
import java.io.InputStream
import java.util.Timer
import java.util.TimerTask

class CountingInputStreamImpl(
    `in`: InputStream,
    private val fileSize: Long,
    private val onProgress: (read: Long, total: Long) -> Unit
) : CountingInputStream(`in`) {
    private val timer = Timer()
    private val task = object : TimerTask() {
        override fun run() {
            onProgress(byteCount, fileSize)
        }
    }

    init {
        timer.schedule(task, 0, 1000)
    }

    override fun close() {
        timer.cancel()
        super.close()
    }
}
