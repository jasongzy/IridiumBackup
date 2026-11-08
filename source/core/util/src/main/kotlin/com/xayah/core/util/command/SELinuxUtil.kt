package com.xayah.core.util.command

import com.xayah.core.util.shellQuote
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.model.ShellResult

object SELinux {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute(*args)

    suspend fun getContext(path: String): ShellResult = run {
        // ls -Zd "$path" | awk 'NF>1{print $1}'
        execute(
            "ls",
            "-Zd",
            shellQuote(path),
            "|",
            "awk 'NF>1{print ${USD}1}'"
        )
    }

    suspend fun chown(uid: UInt, gid: UInt, path: String): ShellResult = run {
        // chown -hR "$uid:$uid" "$path/"
        execute(
            "chown",
            "-hR",
            "$uid:$gid",
            shellQuote("$path/"),
        )
    }

    suspend fun chcon(context: String, path: String): ShellResult = run {
        // chcon -hR "$context" "$path/"
        execute(
            "chcon",
            "-hR",
            shellQuote(context),
            shellQuote("$path/"),
        )
    }
}
