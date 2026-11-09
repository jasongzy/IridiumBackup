package com.xayah.core.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.util.SymbolUtil.LF
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.command.BaseUtil
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.RandomAccessFile

object LogUtil {
    private lateinit var logFile: RandomAccessFile
    private val logName = "log_${DateUtil.getTimestamp()}.txt"
    private val logNamePattern = Regex("""log_\d+\.txt""")
    private const val SEPARATOR = "    "
    private const val TAG_COMMON = "Common    "
    const val TAG_SHELL_IN = "SHELL_IN  "
    const val TAG_SHELL_OUT = "SHELL_OUT "
    const val TAG_SHELL_CODE = "SHELL_CODE"

    suspend fun initialize(context: Context, directory: String) {
        val dir = File(directory)
        dir.mkdirs()
        dir.listFiles()?.filter { logNamePattern.matches(it.name) && it.isFile && it.length() == 0L }
            ?.forEach(File::delete)
        synchronized(this) {
            if (::logFile.isInitialized) logFile.close()
            this.logFile = RandomAccessFile(File(dir, logName), "rw")
        }
        log("Version:    ${BuildConfigUtil.VERSION_NAME}")
        log("Model:      ${Build.MODEL}")
        log("ABIs:       ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}")
        log("SDK:        ${Build.VERSION.SDK_INT}")
        log("Global Namespace:     ${BaseUtil.readLink("1")}")
        log("Namespace:            ${BaseUtil.readLink("self")}")
        log("SU:                   ${BaseUtil.readSuVersion(context.readCustomSUFile().first())}")
        log("${USD}PATH:                ${BaseUtil.readVariable("PATH").trim()}")
        log("${USD}HOME:                ${BaseUtil.readVariable("HOME").trim()}")
    }

    @Synchronized
    private fun appendLine(msg: String) = runCatching {
        val bytes = (msg + LF).toByteArray()
        logFile.seek(logFile.length())
        logFile.write(bytes)
    }

    fun log(tag: String, msg: String): String {
        Log.d(tag, msg)
        appendLine("${DateUtil.formatTimestamp(DateUtil.getTimestamp())}$SEPARATOR$tag$SEPARATOR$msg")
        return msg
    }

    private fun log(msg: String) = log(TAG_COMMON, msg)
}

fun <T> Result<T>.withLog(): Result<T> {
    exceptionOrNull()?.let { LogUtil.log("Exception", it.stackTraceToString()) }
    return this
}
