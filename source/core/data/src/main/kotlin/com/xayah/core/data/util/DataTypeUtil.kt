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

class ArchiveExclusions(packageName: String, val children: List<String>, val namePrefixes: List<String> = emptyList()) {
    val paths = children.map { "$packageName/$it" }
}

fun DataType.archiveExclusions(packageName: String) = when (this) {
    DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE ->
        ArchiveExclusions(packageName, listOf(".ota", "cache", "lib", "code_cache", "no_backup"))
    DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA ->
        ArchiveExclusions(packageName, listOf("cache"), listOf("Backup_"))
    else -> ArchiveExclusions(packageName, emptyList())
}
