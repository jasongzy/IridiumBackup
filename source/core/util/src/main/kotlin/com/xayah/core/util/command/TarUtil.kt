package com.xayah.core.util.command

import com.xayah.core.common.util.toSpaceString
import com.xayah.core.util.shellQuote
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.model.ShellResult

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

    suspend fun test(src: String, extra: String): ShellResult = if (extra.isEmpty()) {
        // tar -tf "$src" > /dev/null 2>&1
        execute(
            "-tf",
            shellQuote(src),
            ">",
            "/dev/null",
            "2>&1",
        )
    } else {
        // zstd -d -c "$src" | tar -tf - > /dev/null 2>&1
        BaseUtil.execute(
            "zstd",
            "-d",
            "-c",
            shellQuote(src),
            "|",
            "tar",
            "-tf",
            "-",
            ">",
            "/dev/null",
            "2>&1",
        )
    }

    suspend fun hasContent(src: String, rootEntry: String, extra: String): ShellResult {
        val filter = "awk -v root=${shellQuote(rootEntry)} " +
            "'${SymbolUtil.USD}0 != root && ${SymbolUtil.USD}0 != root \"/\" { found=1 } END { exit !found }'"
        return if (extra.isEmpty()) {
            execute("-tf", shellQuote(src), "|", filter)
        } else {
            BaseUtil.execute(
                "zstd",
                "-d",
                "-c",
                shellQuote(src),
                "|",
                "tar",
                "-tf",
                "-",
                "|",
                filter,
            )
        }
    }

}
