package com.xayah.core.util.command

import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.util.shellQuote

object PackageUtil {
    suspend fun hasKeystore(su: String, uid: Int, previous: PackageExtraInfo): Boolean {
        val result = BaseUtil.execute("echo 'exec keystore_cli_v2 list' |", shellQuote(su), uid.toString(), log = false)
        val header = result.out.indexOfFirst { it.trim() == "Keys:" }
        if (!result.isSuccess || header < 0) return previous.uid == uid && previous.hasKeystore
        return result.out.drop(header + 1).any { it.isNotBlank() }
    }
}
