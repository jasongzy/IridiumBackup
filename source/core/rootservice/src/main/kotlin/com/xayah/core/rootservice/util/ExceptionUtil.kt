package com.xayah.core.rootservice.util

object ExceptionUtil {
    /**
     * Run block and catch all exception without handling.
     */
    fun tryWithBoolean(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Run block and catch all exception without handling.
     */
    fun tryOn(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    /**
     * Run block and catch all exception with handling.
     */
    fun <T> tryOn(block: () -> T, onException: () -> T): T = try {
        block()
    } catch (_: Exception) {
        onException()
    }
}
