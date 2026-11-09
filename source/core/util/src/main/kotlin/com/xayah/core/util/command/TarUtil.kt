package com.xayah.core.util.command

import com.xayah.core.common.util.toSpaceString
import com.xayah.core.util.shellQuote
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.model.ShellResult
import java.io.IOException

internal fun ShellResult.acceptFileChangedWarnings(): ShellResult = apply {
    val errors = out.filterNot { it.startsWith("Total bytes written:") }
    if (
        code == 1 && errors.isNotEmpty() &&
        errors.all { it.startsWith("tar: ") && it.endsWith(": file changed as we read it") }
    ) {
        code = 0
    }
}

object Tar {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("tar", *args)

    suspend fun compressInCur(cur: String, src: String, dst: String, extra: String): ShellResult {
        val command = mutableListOf("cd", shellQuote(cur), "&&", "tar", "--totals", "-cpf", "-", src)
        if (extra.isNotEmpty()) command += listOf("|", extra)
        command += listOf(">", shellQuote(dst))
        return BaseUtil.execute(*command.toTypedArray())
    }

    suspend fun compress(exclusionList: List<String>, h: String, srcDir: String, src: String, dst: String, extra: String): ShellResult =
        run {
            val exclusion = exclusionList.map { "--exclude=${shellQuote(it)}" }.toSpaceString()
            if (extra.isEmpty()) {
                // tar --totals "$exclusion" $h -cpf - -C "$srcDir" -- "$src" > "$dst"
                execute(
                    "--totals",
                    exclusion,
                    h,
                    "-cpf",
                    "-",
                    "-C",
                    shellQuote(srcDir),
                    "--",
                    shellQuote(src),
                    ">",
                    shellQuote(dst),
                ).acceptFileChangedWarnings()
            } else {
                // tar --totals "$exclusion" $h -cpf - -C "$srcDir" -- "$src" | $extra > "$dst"
                execute(
                    "--totals",
                    exclusion,
                    h,
                    "-cpf",
                    "-",
                    "-C",
                    shellQuote(srcDir),
                    "--",
                    shellQuote(src),
                    "|",
                    extra,
                    ">",
                    shellQuote(dst),
                ).acceptFileChangedWarnings()
            }
        }

    private suspend fun list(src: String, compression: String, output: String): ShellResult =
        if (compression.isEmpty()) {
            execute("-tf", shellQuote(src), output)
        } else {
            // tar stops at its end marker; drain any padding so zstd can verify the complete frame.
            BaseUtil.execute(
                "zstd -dc --", shellQuote(src), "|",
                "( tar -tf -; status=\$?; cat >/dev/null; exit \$status )", output,
            )
        }

    suspend fun test(src: String, extra: String) = list(src, extra, ">/dev/null")

    suspend fun hasContent(src: String, rootEntry: String, extra: String): Result<Boolean> {
        val filter = "awk -v root=${shellQuote(rootEntry)} " +
            "'${SymbolUtil.USD}0 != root && ${SymbolUtil.USD}0 != root \"/\" { found=1 } END { print found ? 1 : 0 }'"
        val listing = list(src, extra, "| $filter")
        if (listing.isSuccess) {
            when (listing.out.lastOrNull()) {
                "1" -> return Result.success(true)
                "0" -> return Result.success(false)
            }
        }
        return Result.failure(IOException("Unable to read $src (exit ${listing.code}): ${listing.outString}"))
    }

}
