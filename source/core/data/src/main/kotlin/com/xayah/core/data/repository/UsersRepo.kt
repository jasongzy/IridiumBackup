package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.data.R
import com.xayah.core.database.dao.AppBackupDao
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.model.OpType
import com.xayah.core.model.UserInfo
import com.xayah.core.rootservice.service.RemoteRootService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UsersRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val rootService: RemoteRootService,
    private val appsDao: PackageDao,
    private val appBackupDao: AppBackupDao,
) {
    fun getUsers(opType: OpType, refreshing: Flow<Boolean>): Flow<List<UserInfo>> = when (opType) {
        OpType.BACKUP -> combine(
            refreshing.distinctUntilChanged().map { rootService.getUsers().map { UserInfo(it.id, it.name) } },
            appBackupDao.observeIndexedUserIds().distinctUntilChanged(),
        ) { active, indexed ->
            val activeIds = active.mapTo(mutableSetOf(), UserInfo::id)
            active.sortedBy(UserInfo::id) + indexed.filterNot(activeIds::contains).map { UserInfo(it, context.getString(R.string.user)) }
        }.distinctUntilChanged().flowOn(defaultDispatcher)

        OpType.RESTORE -> appsDao.queryUserIdsFlow(opType).map { it.map { u -> UserInfo(u, context.getString(R.string.user)) } }
    }
}
