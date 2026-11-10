package com.xayah.feature.crash

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.util.DateUtil
import kotlin.system.exitProcess

internal const val EXTRA_CRASH_INFO = "crashInfo"

class CrashHandler(private val context: Context) {
    fun initialize() {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    putExtra(EXTRA_CRASH_INFO, report(error))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
            exitProcess(1)
        }
    }

    private fun report(error: Throwable) = listOf(
        "================================",
        "Date:     ${DateUtil.formatTimestamp(DateUtil.getTimestamp())}",
        "Version:  ${BuildConfigUtil.VERSION_NAME}",
        "Model:    ${Build.MODEL}",
        "ABIs:     ${Build.SUPPORTED_ABIS.joinToString()}",
        "SDK:      ${Build.VERSION.SDK_INT}",
        "================================",
        error.stackTraceToString().trimEnd(),
    ).joinToString("\n")
}
