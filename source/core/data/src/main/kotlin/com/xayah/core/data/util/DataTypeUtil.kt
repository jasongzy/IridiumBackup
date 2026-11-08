package com.xayah.core.data.util

import com.xayah.core.model.DataType
import com.xayah.core.util.PathUtil

fun DataType.srcDir(userId: Int): String = when (this) {
    DataType.PACKAGE_USER -> PathUtil.getPackageUserDir(userId)
    DataType.PACKAGE_USER_DE -> PathUtil.getPackageUserDeDir(userId)
    DataType.PACKAGE_DATA -> PathUtil.getPackageDataDir(userId)
    DataType.PACKAGE_OBB -> PathUtil.getPackageObbDir(userId)
    DataType.PACKAGE_MEDIA -> PathUtil.getPackageMediaDir(userId)
    else -> ""
}

class ArchiveExclusions(val paths: List<String>, val namePrefixes: List<String> = emptyList()) {
    val tarPatterns get() = paths + namePrefixes.map { "$it*" }
}

fun DataType.archiveExclusions(packageName: String) = when (this) {
    DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE ->
        ArchiveExclusions(listOf(".ota", "cache", "lib", "code_cache", "no_backup").map { "$packageName/$it" })
    DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA ->
        ArchiveExclusions(listOf("$packageName/cache"), listOf("Backup_"))
    else -> ArchiveExclusions(emptyList())
}
