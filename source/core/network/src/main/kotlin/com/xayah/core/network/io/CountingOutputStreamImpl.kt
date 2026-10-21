package com.xayah.core.network.io

import org.apache.commons.io.output.CountingOutputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask

class CountingOutputStreamImpl(
    `out`: OutputStream,
    private val fileSize: Long,
    private val onProgress: (written: Long, total: Long) -> Unit
) : CountingOutputStream(`out`) {
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
