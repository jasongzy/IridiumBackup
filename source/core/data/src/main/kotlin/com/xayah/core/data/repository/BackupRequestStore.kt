package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.SQLITE_BIND_LIMIT
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageTaskRequest
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRequestStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageDao: PackageDao,
) {
    private val pending = AtomicReference<PackageTaskRequest.Backup?>()

    fun take() = pending.getAndSet(null)

    suspend fun prepare(
        ids: Collection<Long>,
        dataStates: Map<Long, PackageDataStates> = emptyMap(),
    ): Boolean {
        val orderedIds = ids.distinct()
        val packagesById = orderedIds.chunked(SQLITE_BIND_LIMIT).flatMap { packageDao.queryByIds(it) }.associateBy(PackageEntity::id)
        val packages = orderedIds.mapNotNull { id ->
            packagesById[id]?.takeIf { it.indexInfo.opType == OpType.BACKUP && !it.extraInfo.blocked }?.let { app ->
                app.copy(
                    extraInfo = app.extraInfo.copy(activated = false),
                    dataStates = dataStates[id]?.copy() ?: app.dataStates.copy(),
                )
            }
        }
        if (packages.isEmpty()) {
            pending.set(null)
            return false
        }
        pending.set(PackageTaskRequest.Backup(
            packages = packages,
            backupItself = context.readBackupItself().first(),
            backupConfigs = context.readBackupConfigs().first(),
        ))
        return true
    }
}
