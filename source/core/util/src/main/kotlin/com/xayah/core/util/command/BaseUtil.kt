package com.xayah.core.util.command

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.common.util.trim
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.util.BinArchiveName
import com.xayah.core.util.LogUtil
import com.xayah.core.util.LogUtil.TAG_SHELL_CODE
import com.xayah.core.util.LogUtil.TAG_SHELL_IN
import com.xayah.core.util.LogUtil.TAG_SHELL_OUT
import com.xayah.core.util.LogUtil.log
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.binDir
import com.xayah.core.util.filesDir
import com.xayah.core.util.logDir
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.shellQuote
import com.xayah.core.util.withIOContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

private val supportedBinaryAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
private val requiredBinaries = setOf("busybox", "tar", "zstd")

private class EnvInitializer : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        shell.newJob()
            .add("nsenter --mount=/proc/1/ns/mnt sh") // Switch to global namespace
            .add("export PATH=${context.binDir()}:${USD}PATH")
            .add("export HOME=${context.filesDir()}")
            .add("set -o pipefail")
            .add("alias awk=${QUOTE}busybox awk$QUOTE")
            .add("alias ps=${QUOTE}busybox ps$QUOTE")
            .exec()
        return true
    }
}

object BaseUtil {
    private val environmentMutex = Mutex()
    private var shellSetup: Deferred<Unit>? = null

    private var isEnvironmentReady = false

    fun configureShell(context: Context) {
        shellSetup = CoroutineScope(Dispatchers.IO).async(start = CoroutineStart.LAZY) {
            Shell.enableVerboseLogging = BuildConfigUtil.ENABLE_VERBOSE
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setInitializers(EnvInitializer::class.java)
                    .setCommands(context.readCustomSUFile().first())
                    .setTimeout(3)
            )
        }
    }

    suspend fun hasRoot(): Boolean = withIOContext {
        try {
            shellSetup?.await()
            Shell.getShell().isRoot
        } catch (_: NoShellException) {
            false
        } catch (e: IOException) {
            log("SU", e.toString())
            false
        }
    }

    suspend fun initializeEnvironment(context: Context): Boolean = withIOContext {
        environmentMutex.withLock {
            if (isEnvironmentReady) return@withLock true
            val binariesReady = installBinaries(context)
            LogUtil.initialize(context, context.logDir())
            isEnvironmentReady = binariesReady
            binariesReady
        }
    }

    suspend fun execute(vararg args: String, log: Boolean = true): ShellResult = withIOContext {
        shellSetup?.await()
        val shellResult = ShellResult(code = -1, input = args.toList().trim(), out = listOf())

        if (log) {
            log(TAG_SHELL_IN, shellResult.inputString)
        }

        Shell.cmd(shellResult.inputString).exec().also { result ->
            shellResult.code = result.code
            shellResult.out = result.out
        }

        if (log) {
            if (shellResult.outString.trim().isNotEmpty())
                log(TAG_SHELL_OUT, shellResult.outString)
            log(TAG_SHELL_CODE, shellResult.code.toString())
        }

        shellResult
    }

    suspend fun mkdirs(dst: String) = withIOContext {
        runCatching {
            val file = File(dst)
            if (file.exists().not()) file.mkdirs() else true
        }
    }

    suspend fun writeIcon(icon: Drawable, dst: String) = withIOContext {
        runCatching {
            val byteArrayOutputStream = ByteArrayOutputStream()
            icon.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            byteArrayOutputStream.flush()
            byteArrayOutputStream.close()
            File(dst).writeBytes(byteArray)
        }
    }

    fun readIconFromPackageName(context: Context, pkgName: String): Drawable? = runCatching { context.packageManager.getApplicationIcon(pkgName) }.getOrNull()

    private fun getBinArchiveAssetPath(): String? {
        val buildAbi = BuildConfigUtil.FLAVOR_abi
        if (buildAbi in supportedBinaryAbis) return BinArchiveName
        return Build.SUPPORTED_ABIS
            .firstOrNull { it in supportedBinaryAbis }
            ?.let { "$it/$BinArchiveName" }
    }

    suspend fun ensureBinaries(context: Context): Boolean = withIOContext {
        environmentMutex.withLock { installBinaries(context) }
    }

    private fun installBinaries(context: Context): Boolean {
        val assetPath = getBinArchiveAssetPath() ?: return false
        val installedAt = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val bin = File(context.binDir())
        val missing = requiredBinaries.filterNotTo(mutableSetOf()) { name ->
            val file = File(bin, name)
            file.isFile && file.canExecute() && file.length() > 0 && file.lastModified() == installedAt
        }
        if (missing.isEmpty()) return true
        if (!bin.isDirectory && !bin.mkdirs()) return false
        val temp = File(bin, ".install")
        try {
            ZipInputStream(context.assets.open(assetPath).buffered()).use { zip ->
                while (missing.isNotEmpty()) {
                    val entry = zip.nextEntry ?: return false
                    if (!missing.remove(entry.name)) continue
                    temp.outputStream().use { if (zip.copyTo(it) == 0L) return false }
                    if (!temp.setExecutable(true) || !temp.setLastModified(installedAt) || !temp.renameTo(File(bin, entry.name))) return false
                }
            }
        } finally {
            temp.delete()
        }
        return true
    }

    suspend fun readLink(pid: String) = run {
        // readlink /proc/$pid/ns/mnt
        execute(
            "readlink",
            "/proc/$pid/ns/mnt",
            log = false,
        ).outString
    }

    suspend fun readVariable(variable: String) = run {
        // echo "$variable"
        execute(
            "echo",
            "${QUOTE}$USD$variable${QUOTE}",
            log = false,
        ).outString
    }

    suspend fun readSuVersion(su: String) = run {
        // su -v
        execute(
            shellQuote(su),
            "-v",
            log = false,
        ).outString
    }
}
