package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.readBackupConfigs
import com.xayah.core.datastore.readBackupItself
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRequestStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageDao: PackageDao,
) {
    data class Options(val backupItself: Boolean = true, val backupConfigs: Boolean = true)

    private val request = MutableStateFlow<List<PackageEntity>>(emptyList())
    private val _options = MutableStateFlow(Options())

    val packages: StateFlow<List<PackageEntity>> = request
    val options = _options.asStateFlow()

    fun setOptions(options: Options) {
        _options.value = options
    }

    suspend fun prepare(
        ids: Collection<Long>,
        dataStates: Map<Long, PackageDataStates> = emptyMap(),
    ) {
        require(ids.isNotEmpty())
        val packagesById = packageDao.queryByIds(ids.toList()).associateBy(PackageEntity::id)
        val packages = ids.mapNotNull { id ->
            packagesById[id]?.let { app ->
                app.copy(
                    extraInfo = app.extraInfo.copy(activated = false),
                    dataStates = dataStates[id]?.copy() ?: app.dataStates.copy(),
                )
            }
        }
        check(packages.isNotEmpty())
        _options.value = Options(
            backupItself = context.readBackupItself().first(),
            backupConfigs = context.readBackupConfigs().first(),
        )
        request.value = packages
    }

    fun clear() {
        request.value = emptyList()
        _options.value = Options()
    }
}
