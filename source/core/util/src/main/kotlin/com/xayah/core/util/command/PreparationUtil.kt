package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil.QUOTE
import com.xayah.core.util.SymbolUtil.USD
import com.xayah.core.util.shellQuote
import com.xayah.core.util.command.BaseUtil.execute
import com.xayah.core.util.model.ShellResult

object PreparationUtil {
    suspend fun listExternalStorage(): ShellResult = run {
        // mount | awk '$3 ~ "/mnt/media_rw/[^/]+$" {print $3}'
        execute(
            "mount",
            "|",
            "awk",
            "'${USD}3 ~ ${QUOTE}/mnt/media_rw/[^/]+${USD}${QUOTE} {print ${USD}3}'",
        )
    }

    suspend fun getExternalStorageType(path: String): ShellResult = run {
        // mount | awk '$3 == "/mnt/media_rw/6EBF-FE14" {print $5}'
        execute(
            "mount",
            "|",
            "awk",
            "-v", "mountpoint=${shellQuote(path)}", "'${USD}3 == mountpoint {print ${USD}5}'",
        )
    }
}

class BackupServiceSettings {
    private val candidates = mutableMapOf<Int, Map<String, String>>()

    suspend fun stop(userId: Int, packageName: String, stop: suspend () -> Boolean): List<suspend () -> Unit>? {
        val names = candidates.getOrPut(userId) {
            listOf(IME, ACCESSIBILITY).mapNotNull { key -> read(userId, key)?.let { key to it } }.toMap()
        }.filterValues { value -> value.split(':').any { it.substringBefore('/') == packageName } }.keys
        val before = names.mapNotNull { key ->
            if (key == ACCESSIBILITY && read(userId, "accessibility_enabled") != "1") return@mapNotNull null
            read(userId, key)?.let { key to it }
        }
        if (!stop()) return null
        val repairs = mutableListOf<suspend () -> Unit>()
        for ((key, original) in before) {
            val after = read(userId, key) ?: continue
            val replacement = replacement(key, packageName, original, after) ?: continue
            repairs += {
                if (read(userId, key) == after && (key != ACCESSIBILITY || read(userId, "accessibility_enabled") == "1")) {
                    val result = execute("settings", "--user", userId.toString(), "put", "secure", key, shellQuote(replacement), log = false)
                    check(result.isSuccess) { result.outString }
                }
            }
        }
        return repairs
    }

    private suspend fun read(userId: Int, key: String): String? =
        execute("settings", "--user", userId.toString(), "get", "secure", key, log = false)
            .takeIf { it.isSuccess }?.out?.singleOrNull()?.trim()?.let { if (it == "null") "" else it }

    companion object {
        private const val IME = "default_input_method"
        private const val ACCESSIBILITY = "enabled_accessibility_services"

        internal fun replacement(key: String, owner: String, before: String, after: String): String? {
            if (before == after) return null
            if (key == IME) return before.takeIf { it.substringBefore('/') == owner && after.isEmpty() }
            val current = after.split(':').filter(String::isNotEmpty)
            val lost = before.split(':').filter { it.substringBefore('/') == owner && it !in current }
            return (current + lost).joinToString(":").takeIf { lost.isNotEmpty() }
        }
    }
}
