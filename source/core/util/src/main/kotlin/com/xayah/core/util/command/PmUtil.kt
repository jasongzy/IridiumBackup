package com.xayah.core.util.command

import android.os.Build
import com.xayah.core.util.PathUtil
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.model.ShellResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object Pm {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("pm", *args)
    suspend fun install(userId: Int, src: String): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // pm install --user "$userId" -r -t "$src"
        execute(
            "install",
            "--user",
            "$QUOTE$userId$QUOTE",
            "-r",
            "-t",
            "$QUOTE$src$QUOTE",
        )
    } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
        // pm install -i com.android.vending --user "$userId" -r -t "$src"
        execute(
            "install",
            "-i",
            "com.android.vending",
            "--user",
            "$QUOTE$userId$QUOTE",
            "-r",
            "-t",
            "$QUOTE$src$QUOTE",
        )
    } else {
        // pm install --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -r -t "$src"
        execute(
            "install",
            "--bypass-low-target-sdk-block",
            "-i",
            "com.android.vending",
            "--user",
            "$QUOTE$userId$QUOTE",
            "-r",
            "-t",
            "$QUOTE$src$QUOTE",
        )
    }

    object Install {
        suspend fun install(userId: Int, apks: List<String>): ShellResult = installApkSession(
            apks = apks,
            createSession = { create(userId) },
            execute = { execute(*it.toTypedArray()) },
        )

        private suspend fun create(userId: Int): ShellResult = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // pm install-create --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            // pm install-create -i com.android.vending --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        } else {
            // pm install-create --bypass-low-target-sdk-block -i com.android.vending --user "$userId" -t | grep -E -o '[0-9]+'
            execute(
                "install-create",
                "--bypass-low-target-sdk-block",
                "-i",
                "com.android.vending",
                "--user",
                "$QUOTE$userId$QUOTE",
                "-t",
                "|",
                "grep -E -o '[0-9]+'",
            )
        }
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
            if (result.isSuccess) session = result.outString.trim()
        }
        currentCoroutineContext().ensureActive()
        if (session.isEmpty()) return ShellResult(-1, emptyList(), output)
        for (apk in apks) {
            val result = execute(listOf("install-write", "$QUOTE$session$QUOTE", "$QUOTE${PathUtil.getFileName(apk)}$QUOTE", "$QUOTE$apk$QUOTE"))
            output.addAll(result.out)
            if (!result.isSuccess) return result.copy(out = output)
        }
        val result = execute(listOf("install-commit", "$QUOTE$session$QUOTE"))
        committed = result.isSuccess
        return result.copy(out = output + result.out)
    } finally {
        if (session.isNotEmpty() && !committed) withContext(NonCancellable) {
            execute(listOf("install-abandon", "$QUOTE$session$QUOTE"))
        }
    }
}
