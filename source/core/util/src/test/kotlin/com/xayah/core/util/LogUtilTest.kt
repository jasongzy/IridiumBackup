package com.xayah.core.util

import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogUtilTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun concurrentWritersPreserveEveryLogEntry() {
        val file = temporary.newFile()
        val field = LogUtil.javaClass.getDeclaredField("logFile").apply { isAccessible = true }
        val append = LogUtil.javaClass.declaredMethods.single { it.name.startsWith("appendLine-") }
            .apply { isAccessible = true }
        val previous = field.get(LogUtil)
        val executor = Executors.newFixedThreadPool(8)
        try {
            RandomAccessFile(file, "rw").use { output ->
                field.set(LogUtil, output)
                val start = CountDownLatch(1)
                val jobs = (0 until 8).map { worker ->
                    executor.submit {
                        check(start.await(5, TimeUnit.SECONDS))
                        repeat(500) { entry -> append.invoke(LogUtil, "$worker:$entry 备份") }
                    }
                }
                start.countDown()
                jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            }
            val lines = file.readLines()
            val expected = (0 until 8).flatMap { worker -> (0 until 500).map { entry -> "$worker:$entry 备份" } }.toSet()
            assertEquals(expected.size, lines.size)
            assertEquals(expected, lines.toSet())
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            field.set(LogUtil, previous)
        }
    }
}
