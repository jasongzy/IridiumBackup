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
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@ActivityRetainedScoped
class PackageRequestStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageDao: PackageDao,
) {
    private val backup = AtomicReference<PackageTaskRequest.Backup?>()
    private val restore = AtomicReference<List<PackageEntity>>(emptyList())

    fun takeBackup() = backup.getAndSet(null)
    fun takeRestore() = restore.getAndSet(emptyList())

    fun prepareRestore(packages: List<PackageEntity>) {
        restore.set(packages)
    }

    private suspend fun selectedPackages(ids: Collection<Long>): List<PackageEntity> {
        val ordered = ids.distinct()
        val rows = ordered.chunked(SQLITE_BIND_LIMIT).flatMap { packageDao.queryByIds(it) }.associateBy(PackageEntity::id)
        return ordered.mapNotNull(rows::get)
    }

    suspend fun prepareRestore(ids: Collection<Long>, cloud: String, backupDir: String): Boolean {
        val packages = selectedPackages(ids).filter {
            it.indexInfo.opType == OpType.RESTORE && it.indexInfo.cloud == cloud && it.indexInfo.backupDir == backupDir
        }
        prepareRestore(packages)
        return packages.isNotEmpty()
    }

    suspend fun prepareBackup(
        ids: Collection<Long>,
        dataStates: Map<Long, PackageDataStates> = emptyMap(),
    ): Boolean {
        val packages = selectedPackages(ids).filter { it.indexInfo.opType == OpType.BACKUP && !it.extraInfo.blocked }.map { app ->
            app.copy(
                extraInfo = app.extraInfo.copy(activated = false),
                dataStates = (dataStates[app.id] ?: app.dataStates).copy(),
            )
        }
        if (packages.isEmpty()) {
            backup.set(null)
            return false
        }
        backup.set(PackageTaskRequest.Backup(
            packages = packages,
            backupItself = context.readBackupItself().first(),
            backupConfigs = context.readBackupConfigs().first(),
        ))
        return true
    }
}
