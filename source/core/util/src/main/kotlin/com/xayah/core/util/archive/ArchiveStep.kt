package com.xayah.core.util.archive

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

suspend fun <T> finishArchiveStep(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    val result = withContext(NonCancellable) { block() }
    currentCoroutineContext().ensureActive()
    return result
}
