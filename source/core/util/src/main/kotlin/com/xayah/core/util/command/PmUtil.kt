package com.xayah.core.util.command

import android.os.Build
import com.xayah.core.util.PathUtil
import com.xayah.core.util.shellQuote
import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object Pm {
    private suspend fun execute(args: List<String>) = BaseUtil.execute("pm", *args.map(::shellQuote).toTypedArray())

    suspend fun install(userId: Int, apks: List<String>): ShellResult {
        val options = buildList {
            addAll(listOf("--user", userId.toString(), "-r", "-t"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) addAll(listOf("-i", "com.android.vending"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add("--bypass-low-target-sdk-block")
        }
        return if (apks.size == 1) execute(listOf("install") + options + apks.single())
        else installApkSession(apks, { execute(listOf("install-create") + options) }, ::execute)
    }
}

internal suspend fun installApkSession(
    apks: List<String>,
    createSession: suspend () -> ShellResult,
    execute: suspend (List<String>) -> ShellResult,
): ShellResult {
    var session = ""
    var committed = false
    val output = mutableListOf<String>()
    try {
        withContext(NonCancellable) {
            val result = createSession()
            output.addAll(result.out)
            if (result.isSuccess) session = result.out.lastOrNull()?.substringAfterLast('[', "")?.removeSuffix("]")
                ?.toIntOrNull()?.toString().orEmpty()
        }
        currentCoroutineContext().ensureActive()
        if (session.isEmpty()) return ShellResult(-1, emptyList(), output)
        for (apk in apks) {
            val result = execute(listOf("install-write", session, PathUtil.getFileName(apk), apk))
            output.addAll(result.out)
            if (!result.isSuccess) return result.copy(out = output)
        }
        val result = execute(listOf("install-commit", session))
        committed = result.isSuccess
        return result.copy(out = output + result.out)
    } finally {
        if (session.isNotEmpty() && !committed) withContext(NonCancellable) {
            execute(listOf("install-abandon", session))
        }
    }
}
