package com.xayah.core.model

import com.xayah.core.model.database.PackageEntity

sealed interface PackageTaskRequest {
    val packages: List<PackageEntity>

    data class Backup(
        override val packages: List<PackageEntity>,
        val backupItself: Boolean,
        val backupConfigs: Boolean,
    ) : PackageTaskRequest

    data class Restore(override val packages: List<PackageEntity>, val userId: Int) : PackageTaskRequest {
        fun targetUser(app: PackageEntity): Int = if (userId == -1) app.userId else userId

        fun conflictingTarget(): AppKey? {
            val seen = mutableSetOf<AppKey>()
            for (app in packages) {
                val target = AppKey(app.packageName, targetUser(app))
                if (!seen.add(target)) return target
            }
            return null
        }
    }
}
