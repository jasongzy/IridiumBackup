package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.datastore.DashboardSortField
import com.xayah.core.datastore.DashboardFilterPreference
import com.xayah.core.datastore.LabelFilterMode
import com.xayah.core.datastore.editDashboardLabelFilters
import com.xayah.core.datastore.readDashboardLabelFilters
import com.xayah.core.datastore.renameLabelFilter
import com.xayah.core.datastore.readDashboardFilterPreference
import com.xayah.core.datastore.readDashboardSortPreference
import com.xayah.core.datastore.saveDashboardFilterPreference
import com.xayah.core.datastore.saveDashboardSortPreference
import com.xayah.core.model.App
import com.xayah.core.model.AppKey
import com.xayah.core.model.File
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.Target
import com.xayah.core.model.UserInfo
import com.xayah.core.util.module.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

@ActivityRetainedScoped
class ListDataRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usersRepo: UsersRepo,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
    private val workRepo: WorkRepo,
) {
    private lateinit var target: Target
    private lateinit var listData: Flow<ListData>
    private var persistDashboardState = false

    private lateinit var selectedFiles: Flow<Long>
    private lateinit var selectionMode: MutableStateFlow<Boolean>
    private lateinit var searchQuery: MutableStateFlow<String>
    private lateinit var showFilterSheet: MutableStateFlow<Boolean>
    private lateinit var sortIndex: MutableStateFlow<Int>
    private lateinit var sortType: MutableStateFlow<SortType>
    private lateinit var isUpdating: Flow<Boolean>
    private var sessionLabelFilters = MutableStateFlow(emptyMap<String, LabelFilterMode>())
    private lateinit var labelFilters: Flow<Map<String, LabelFilterMode>>

    // Apps
    private lateinit var showDataItemsSheet: MutableStateFlow<Boolean>
    private lateinit var filters: Flow<DashboardFilterPreference>
    private lateinit var sessionFilters: MutableStateFlow<DashboardFilterPreference>
    private lateinit var selectedUserId: MutableStateFlow<Int?>
    private lateinit var userList: Flow<List<UserInfo>>
    private lateinit var appList: Flow<List<App>>
    private lateinit var selectedAppKeys: MutableStateFlow<Set<AppKey>>
    private lateinit var pkgUserSet: Flow<Set<String>> // "${pkgName}-${userId}"

    // Files
    private lateinit var fileList: Flow<List<File>>

    fun initialize(target: Target, opType: OpType, cloudName: String, backupDir: String) {
        this.target = target
        persistDashboardState = target == Target.Apps && opType == OpType.BACKUP
        sessionLabelFilters = MutableStateFlow(emptyMap())
        labelFilters = if (persistDashboardState) context.readDashboardLabelFilters() else sessionLabelFilters
        when (target) {
            Target.Apps -> {
                selectedAppKeys = MutableStateFlow(emptySet())
                selectionMode = MutableStateFlow(false)
                searchQuery = MutableStateFlow("")
                showFilterSheet = MutableStateFlow(false)
                sortIndex = MutableStateFlow(0)
                sortType = MutableStateFlow(SortType.ASCENDING)
                isUpdating = when (opType) {
                    OpType.BACKUP -> workRepo.isAppRefreshRunning()
                    OpType.RESTORE -> combine(workRepo.isAppRefreshRunning(), workRepo.isLoadAppBackupsRunning()) { refresh, loadBackups -> refresh || loadBackups }
                }

                showDataItemsSheet = MutableStateFlow(false)
                sessionFilters = MutableStateFlow(DashboardFilterPreference())
                filters = if (persistDashboardState) context.readDashboardFilterPreference() else sessionFilters
                selectedUserId = MutableStateFlow(null)
                userList = usersRepo.getUsers(opType, isUpdating)

                listData = getAppListData()
                pkgUserSet = when (opType) {
                    OpType.BACKUP -> {
                        flowOf(emptySet())
                    }

                    OpType.RESTORE -> {
                        appsRepo.getInstalledApps(userList)
                    }
                }
                appList = combine(
                    appsRepo.getApps(opType = opType, listData = listData, pkgUserSet = pkgUserSet, refs = labelsRepo.getAppRefsFlow(), cloudName = cloudName, backupDir = backupDir),
                    selectedAppKeys,
                ) { apps, keys ->
                    apps.map { it.copy(selected = it.key in keys) }
                }
            }

            Target.Files -> {
                selectionMode = MutableStateFlow(false)
                selectedFiles = filesRepo.countSelectedFiles(opType)
                searchQuery = MutableStateFlow("")
                showFilterSheet = MutableStateFlow(false)
                sortIndex = MutableStateFlow(0)
                sortType = MutableStateFlow(SortType.ASCENDING)
                isUpdating = when (opType) {
                    OpType.BACKUP -> combine(workRepo.isAppRefreshRunning(), workRepo.isFastInitAndUpdateFilesRunning()) { refresh, fast -> refresh || fast }
                    OpType.RESTORE -> combine(workRepo.isAppRefreshRunning(), workRepo.isLoadFileBackupsRunning()) { refresh, loadBackups -> refresh || loadBackups }
                }

                listData = getFileListData()
                fileList = filesRepo.getFiles(opType = opType, listData = listData, refs = labelsRepo.getFileRefsFlow(), cloudName = cloudName, backupDir = backupDir)
            }
        }
    }

    private fun getAppListData(): Flow<ListData.Apps> = combine(
        combine(selectedAppKeys, selectionMode) { keys, mode -> keys.size.toLong() to mode },
        searchQuery,
        showFilterSheet,
        sortIndex,
        sortType,
        isUpdating,
        labelFilters,
        showDataItemsSheet,
        filters,
        appsRepo.scanScope,
        selectedUserId,
        userList,
    ) { (selected, mode), sQuery, sFSheet, sIndex, sType, iUpdating, lIds, sDISheet, filters, scanScope, userId, users ->
        val index = users.indexOfFirst { it.id == userId }.coerceAtLeast(0)
        ListData.Apps(
            selected, mode, sQuery, sFSheet, sIndex, sType, iUpdating, lIds, sDISheet,
            filters, scanScope, index, users,
        )
    }

    private fun getFileListData(): Flow<ListData.Files> = combine(
        selectedFiles,
        selectionMode,
        searchQuery,
        showFilterSheet,
        sortIndex,
        sortType,
        isUpdating,
        labelFilters,
    ) { s, mode, sQuery, sFSheet, sIndex, sType, iUpdating, lIds ->
        ListData.Files(s, mode, sQuery, sFSheet, sIndex, sType, iUpdating, lIds)
    }

    fun getListData(): Flow<ListData> = listData

    fun getAppList(): Flow<List<App>> = appList

    fun getSelectedAppKeys(): StateFlow<Set<AppKey>> = selectedAppKeys

    suspend fun getSelectedAppIds(): Set<Long> = appList.first()
        .asSequence()
        .filter(App::selected)
        .map(App::id)
        .toSet()

    fun getFileList(): Flow<List<File>> = fileList

    suspend fun setFilters(value: DashboardFilterPreference, resetLabels: Boolean = false) {
        if (persistDashboardState) context.saveDashboardFilterPreference(value, resetLabels) else {
            sessionFilters.value = value
            if (resetLabels) sessionLabelFilters.value = emptyMap()
        }
    }

    suspend fun setAppSelected(key: AppKey, selected: Boolean) {
        selectedAppKeys.update { if (selected) it + key else it - key }
    }

    suspend fun enterAppSelection(key: AppKey) {
        selectionMode.emit(true)
        setAppSelected(key, true)
    }

    suspend fun selectApps(keys: Collection<AppKey>) {
        selectedAppKeys.update { it + keys }
    }

    suspend fun unselectApps(keys: Collection<AppKey>) {
        val candidates = keys.toSet()
        selectedAppKeys.update { it - candidates }
    }

    suspend fun reverseAppSelection(keys: Collection<AppKey>) {
        val candidates = keys.toSet()
        selectedAppKeys.update { (it - candidates) + (candidates - it) }
    }

    fun retainAppSelection(keys: Collection<AppKey>) {
        val candidates = keys.toSet()
        selectedAppKeys.update { it.intersect(candidates) }
    }

    fun clearAppSelection() {
        selectedAppKeys.value = emptySet()
        selectionMode.value = false
    }

    suspend fun setSortIndex(block: (Int) -> Int) {
        sortIndex.emit(block(sortIndex.value))
        saveAppSortPreference()
    }

    suspend fun loadAppSortPreference() {
        if (target != Target.Apps) return
        val preference = context.readDashboardSortPreference().first()
        sortIndex.emit(preference.field.toSortIndex())
        sortType.emit(if (preference.ascending) SortType.ASCENDING else SortType.DESCENDING)
    }

    suspend fun setSortType(block: (SortType) -> SortType) {
        sortType.emit(block(sortType.value))
        saveAppSortPreference()
    }

    suspend fun setSearchQuery(value: String) {
        searchQuery.emit(value)
    }

    suspend fun setUser(userId: Int) {
        selectedUserId.emit(userId)
    }

    suspend fun setShowFilterSheet(value: Boolean) {
        showFilterSheet.emit(value)
    }

    suspend fun setShowDataItemsSheet(value: Boolean) {
        showDataItemsSheet.emit(value)
    }

    private suspend fun editLabelFilters(edit: (Map<String, LabelFilterMode>) -> Map<String, LabelFilterMode>) {
        if (persistDashboardState) context.editDashboardLabelFilters(edit) else sessionLabelFilters.update(edit)
    }

    suspend fun cycleLabelFilter(label: String) = editLabelFilters { filters ->
        when (filters[label]) {
            null -> filters + (label to LabelFilterMode.INCLUDE)
            LabelFilterMode.INCLUDE -> filters + (label to LabelFilterMode.EXCLUDE)
            LabelFilterMode.EXCLUDE -> filters - label
        }
    }

    suspend fun removeLabelFilter(label: String) = editLabelFilters { it - label }

    fun onLabelChanged(oldLabel: String, newLabel: String?) {
        if (!persistDashboardState) sessionLabelFilters.update { it.renameLabelFilter(oldLabel, newLabel) }
    }

    private suspend fun saveAppSortPreference() {
        if (target != Target.Apps) return
        context.saveDashboardSortPreference(
            field = sortIndex.value.toSortField(),
            ascending = sortType.value == SortType.ASCENDING,
        )
    }

    private fun DashboardSortField.toSortIndex(): Int = when (this) {
        DashboardSortField.INSTALLED -> 1
        DashboardSortField.DATA_SIZE -> 2
        DashboardSortField.UPDATED -> 3
        DashboardSortField.BACKED_UP -> 4
        DashboardSortField.NAME -> 0
    }

    private fun Int.toSortField(): DashboardSortField = when (this) {
        1 -> DashboardSortField.INSTALLED
        2 -> DashboardSortField.DATA_SIZE
        3 -> DashboardSortField.UPDATED
        4 -> DashboardSortField.BACKED_UP
        else -> DashboardSortField.NAME
    }
}

sealed class ListData(
    open val selected: Long,
    open val selectionMode: Boolean,
    open val searchQuery: String,
    open val showFilterSheet: Boolean,
    open val sortIndex: Int,
    open val sortType: SortType,
    open val isUpdating: Boolean,
    open val labelFilters: Map<String, LabelFilterMode>,
) {
    data class Apps(
        override val selected: Long,
        override val selectionMode: Boolean,
        override val searchQuery: String,
        override val showFilterSheet: Boolean,
        override val sortIndex: Int,
        override val sortType: SortType,
        override val isUpdating: Boolean,
        override val labelFilters: Map<String, LabelFilterMode>,
        val showDataItemsSheet: Boolean,
        val filters: DashboardFilterPreference,
        val scanScope: AppScanScope,
        val userIndex: Int,
        val userList: List<UserInfo>,
    ) : ListData(selected, selectionMode, searchQuery, showFilterSheet, sortIndex, sortType, isUpdating, labelFilters)

    data class Files(
        override val selected: Long,
        override val selectionMode: Boolean,
        override val searchQuery: String,
        override val showFilterSheet: Boolean,
        override val sortIndex: Int,
        override val sortType: SortType,
        override val isUpdating: Boolean,
        override val labelFilters: Map<String, LabelFilterMode>,
    ) : ListData(selected, selectionMode, searchQuery, showFilterSheet, sortIndex, sortType, isUpdating, labelFilters)
}
