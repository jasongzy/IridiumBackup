package com.xayah.core.util.command

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.topjohnwu.superuser.Shell
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.common.util.trim
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.util.BinArchiveName
import com.xayah.core.util.FileUtil
import com.xayah.core.util.LogUtil
import com.xayah.core.util.LogUtil.TAG_SHELL_CODE
import com.xayah.core.util.LogUtil.TAG_SHELL_IN
import com.xayah.core.util.LogUtil.TAG_SHELL_OUT
import com.xayah.core.util.LogUtil.log
import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.binArchivePath
import com.xayah.core.util.binDir
import com.xayah.core.util.filesDir
import com.xayah.core.util.logDir
import com.xayah.core.util.model.ShellResult
import com.xayah.core.util.withIOContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.lingala.zip4j.ZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private val supportedBinaryAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
private val requiredBinaries = setOf("busybox", "tar", "zstd")

private class EnvInitializer : Shell.Initializer() {
    companion object {
        fun initShell(shell: Shell, context: Context) {
            shell.newJob()
                .add("nsenter --mount=/proc/1/ns/mnt sh") // Switch to global namespace
                .add("export PATH=${context.binDir()}:${USD}PATH")
                .add("export HOME=${context.filesDir()}")
                .add("set -o pipefail") // Ensure that the exit code of each command is correct.
                .add("alias awk=${QUOTE}busybox awk$QUOTE")
                .add("alias ps=${QUOTE}busybox ps$QUOTE")
                .exec()
        }
    }

    override fun onInit(context: Context, shell: Shell): Boolean {
        initShell(shell, context)
        return true
    }
}

object BaseUtil {
    private suspend fun getShellBuilder(context: Context) = Shell.Builder.create()
        .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
        .setInitializers(EnvInitializer::class.java)
        .setCommands(context.readCustomSUFile().first())
        .setTimeout(3)

    private suspend fun getNewShell(context: Context): Shell? = runCatching { getShellBuilder(context).build() }.getOrNull()

    suspend fun initializeEnvironment(context: Context) = run {
        if (hasBaseBinaries(context).not()) {
            releaseBase(context)
        }

        // Set up shell environment.
        Shell.enableVerboseLogging = BuildConfigUtil.ENABLE_VERBOSE
        Shell.setDefaultBuilder(getShellBuilder(context))

        // Set up LogUtil.
        LogUtil.initialize(context, context.logDir())
    }

    suspend fun execute(vararg args: String, shell: Shell? = null, log: Boolean = true): ShellResult = withIOContext {
        val shellResult = ShellResult(code = -1, input = args.toList().trim(), out = listOf())

        if (log) {
            log { TAG_SHELL_IN to shellResult.inputString }
        }

        if (shell == null) {
            Shell.cmd(shellResult.inputString).exec().also { result ->
                shellResult.code = result.code
                shellResult.out = result.out
            }
        } else {
            val outList = mutableListOf<String>()
            shell.newJob().to(outList, outList).add(shellResult.inputString).exec().also { result ->
                shellResult.code = result.code
                shellResult.out = outList
            }
        }

        if (log) {
            if (shellResult.outString.trim().isNotEmpty())
                log { TAG_SHELL_OUT to shellResult.outString }
            log { TAG_SHELL_CODE to shellResult.code.toString() }
        }

        shellResult
    }

    suspend fun execute(vararg args: String, shell: Shell, log: Boolean = true, timeout: Long): ShellResult = withIOContext {
        var shellResult = ShellResult(code = -1, input = args.toList().trim(), out = listOf())

        val job = launch {
            shellResult = execute(args = args, shell = shell, log = log)
        }
        delay(50)
        runCatching {
            if (timeout != -1L) {
                shell.waitAndClose(timeout, TimeUnit.SECONDS)
            } else {
                shell.waitAndClose()
            }
        }.onFailure {
            log("Execution timeout.")
        }
        job.join()

        shellResult
    }

    suspend fun kill(context: Context, vararg keys: String) {
        val shell = getNewShell(context)
        if (shell != null) {
            // ps -A | grep -w $key1 | grep -w $key2 | ... | awk 'NF>1{print $1}' | xargs kill -9
            val keysArg = keys.map { "| grep -w $it" }.toTypedArray()
            execute(
                "ps -A",
                *keysArg,
                "| awk 'NF>1{print ${USD}1}'",
                "| xargs kill -9",
                shell = shell,
                timeout = -1
            )
        } else {
            log { "kill" to "Failed to get a new shell!" }
        }
    }

    suspend fun cancelArchiveCommands(context: Context) {
        val shell = getNewShell(context) ?: return
        val bin = context.binDir()
        val command = """
            for process in /proc/[0-9]*/exe; do
                for tool in '$bin/tar' '$bin/zstd'; do
                    [ ! -L "${USD}tool" ] && [ "${USD}process" -ef "${USD}tool" ] || continue
                    pid=${USD}{process#/proc/}
                    pid=${USD}{pid%/exe}
                    kill -9 "${USD}pid" 2>/dev/null
                    break
                done
            done
        """.trimIndent()
        execute(command, shell = shell, timeout = -1)
    }

    suspend fun killPackage(context: Context, userId: Int, packageName: String) {
        val shell = getNewShell(context)
        if (shell != null) {
            val cmd = """
            until [[ $USD(dumpsys activity processes | grep "packageList" | cut -d '{' -f2 | cut -d '}' -f1 | egrep -w "$packageName" | sed -n '1p') = "" ]]; do
                killall -9 "$packageName" &>/dev/null
                am force-stop --user "$userId" "$packageName" &>/dev/null
                am kill "$packageName" &>/dev/null
            done
        """.trimIndent()
            execute(cmd, shell = shell, timeout = 3)
        } else {
            log { "killPackage" to "Failed to get a new shell!" }
        }
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

    suspend fun readIcon(context: Context, src: String): Drawable? = withIOContext {
        runCatching {
            val bytes = File(src).readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).toDrawable(context.resources)
        }.getOrNull()
    }

    @SuppressLint("SetWorldWritable", "SetWorldReadable")
    fun File.setAllPermissions(): Boolean = run {
        setExecutable(true, false).also {
            if (it.not()) return@run false
        }
        setWritable(true, false).also {
            if (it.not()) return@run false
        }
        setReadable(true, false).also {
            if (it.not()) return@run false
        }
    }

    /**
     * Unzip and return file headers.
     */
    private suspend fun unzip(src: String, dst: String): List<String> = withIOContext {
        ZipFile(src).use { zip ->
            zip.extractAll(dst)
            zip.fileHeaders.map { it.fileName }
        }
    }

    private suspend fun releaseAsset(context: Context, src: String, child: String) {
        withIOContext {
            val asset = File(context.filesDir(), child)
            if (asset.exists().not()) {
                FileOutputStream(asset).use { output ->
                    context.assets.open(src).use { input ->
                        input.copyTo(output)
                    }
                }
                asset.setExecutable(true)
                asset.setReadable(true)
                asset.setWritable(true)
            }
        }
    }

    private fun hasBaseBinaries(context: Context): Boolean =
        requiredBinaries.all { File(context.binDir(), it).isFile }

    private fun getBinArchiveAssetPath(): String? {
        val buildAbi = BuildConfigUtil.FLAVOR_abi
        if (buildAbi in supportedBinaryAbis) return BinArchiveName
        return Build.SUPPORTED_ABIS
            .firstOrNull { it in supportedBinaryAbis }
            ?.let { "$it/$BinArchiveName" }
    }

    suspend fun releaseBase(context: Context): Boolean = withIOContext {
        val bin = File(context.binDir())
        val binArchive = File(context.binArchivePath())

        // Remove old bin files
        FileUtil.deleteRecursively(bin.path)
        FileUtil.deleteRecursively(binArchive.path)

        // Release binaries
        val assetPath = getBinArchiveAssetPath() ?: return@withIOContext false
        releaseAsset(context = context, src = assetPath, child = BinArchiveName)
        if (unzip(src = context.binArchivePath(), dst = context.binDir()).isEmpty()) {
            return@withIOContext false
        }

        // All binaries need full permissions
        bin.listFiles()?.forEach { file ->
            if (file.setAllPermissions().not()) return@withIOContext false
        } ?: return@withIOContext false

        if (hasBaseBinaries(context).not()) return@withIOContext false

        // Remove binary archive
        FileUtil.deleteRecursively(binArchive.path)

        return@withIOContext true
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
            su,
            "-v",
            log = false,
        ).outString
    }
}
