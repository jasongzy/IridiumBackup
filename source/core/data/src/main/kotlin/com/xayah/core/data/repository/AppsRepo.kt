package com.xayah.core.data.repository

import android.content.Context
import com.xayah.core.datastore.DashboardFilterPreference
import com.xayah.core.datastore.LabelFilterMode
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.xayah.core.data.R
import com.xayah.core.data.util.archiveExclusions
import com.xayah.core.data.util.srcDir
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.datastore.readLoadSystemApps
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.App
import com.xayah.core.model.AppKey
import com.xayah.core.model.BlacklistAppItem
import com.xayah.core.model.ConfigsPackageRestoreName
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.DefaultPreserveId
import com.xayah.core.model.OpType
import com.xayah.core.model.PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.SettingsData
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStatesEntity
import com.xayah.core.model.database.PackageDisplayStatsEntity
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.model.database.PackagePermissionsEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.PackageUpdateEntity
import com.xayah.core.model.database.asExternalModel
import com.xayah.core.model.toPackageEntity
import com.xayah.core.model.toRestoreConfig
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.DateUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PackageUtil
import com.xayah.core.util.withLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

private const val APP_INIT_BATCH_SIZE = 50
private val appInitializationMutex = Mutex()

data class AppScanScope(val loadSystemApps: Boolean, private val backedUp: Set<AppKey> = emptySet()) {
    fun includes(packageName: String, userId: Int, isSystem: Boolean) =
        loadSystemApps || !isSystem || AppKey(packageName, userId) in backedUp

    fun matchesType(filters: DashboardFilterPreference, packageName: String, userId: Int, isSystem: Boolean, fromBackup: Boolean): Boolean =
        if (loadSystemApps) {
            if (isSystem) filters.systemApps else filters.nonSystemApps
        } else fromBackup || includes(packageName, userId, isSystem)
}

private data class FastInitializationBatch(
    val userId: Int,
    val outdatedPackages: Set<String>,
    val missingPackages: List<android.content.pm.PackageInfo>,
    val changedApps: List<PackageEntity>,
)

class AppsRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(Default) private val defaultDispatcher: CoroutineDispatcher,
    private val appsDao: PackageDao,
    private val packageRepo: PackageRepository,
    private val rootService: RemoteRootService,
    private val settingsDataRepo: SettingsDataRepo,
    private val pathUtil: PathUtil,
    private val cloudRepo: CloudRepository,
    private val appBackupRepository: AppBackupRepository,
    private val labelsRepo: LabelsRepo,
    private val xposedModuleDetector: XposedModuleDetector,
) {
    fun getInstalledApps(users: Flow<List<UserInfo>>): Flow<Set<String>> = users.map { u ->
        val set = mutableSetOf<String>()
        u.forEach {
            rootService.getInstalledPackagesAsUser(0, it.id).getOrNull().orEmpty()
                .mapTo(set) { p -> "${p.packageName}-${it.id}" }
        }
        set
    }

    fun getApp(id: Long) = appsDao.queryPackageFlow(id).flowOn(defaultDispatcher)

    fun getApp(packageName: String, userId: Int) =
        appsDao.queryFlow(packageName, OpType.BACKUP, userId, DefaultPreserveId)
            .flowOn(defaultDispatcher)

    fun observeArchivedXposedApps(): Flow<Set<AppKey>> =
        appsDao.observeXposedAppKeys(OpType.RESTORE).map { it.toSet() }.distinctUntilChanged().flowOn(defaultDispatcher)

    fun getApps(
        opType: OpType,
        listData: Flow<ListData>,
        pkgUserSet: Flow<Set<String>>,
        refs: Flow<List<LabelAppCrossRefEntity>>,
        cloudName: String,
        backupDir: String
    ): Flow<List<App>> = combine(
        listData,
        pkgUserSet,
        refs.map { items -> items.groupBy { AppKey(it.packageName, it.userId) }.mapValues { (_, refs) -> refs.mapTo(mutableSetOf()) { it.label } } },
        when (opType) {
            OpType.BACKUP -> appsDao.queryPackagesFlow(opType = opType, blocked = false)
            OpType.RESTORE -> appsDao.queryPackagesFlow(opType = opType, cloud = cloudName, backupDir = backupDir)
        }
    ) { lData, pSet, labelsByApp, apps ->
        val data = lData.castTo<ListData.Apps>()
        val filters = data.labelFilters
        val included = filters.filterValues { it == LabelFilterMode.INCLUDE }.keys
        val excluded = filters.filterValues { it == LabelFilterMode.EXCLUDE }.keys
        apps.asSequence()
            .filter { app ->
                data.scanScope.matchesType(data.filters, app.packageName, app.userId, app.isSystemApp, opType == OpType.RESTORE)
            }
            .filter { app ->
                if (app.extraInfo.enabled) data.filters.unfrozenApps else data.filters.frozenApps
            }
            .filter { app -> data.filters.matchesXposed(app.packageInfo.isXposedModule) }
            .filter { app ->
                if (opType == OpType.BACKUP || app.pkgUserKey in pSet) data.filters.installedApps else data.filters.notInstalledApps
            }
            .filter { app ->
                val appLabels = labelsByApp[AppKey(app.packageName, app.userId)].orEmpty()
                data.filters.matchesIncludedLabels(appLabels, included) && appLabels.none(excluded::contains)
            }
            .toList()
            .map(PackageEntity::asExternalModel)
    }.flowOn(defaultDispatcher)


    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val scanScope = context.readLoadSystemApps().distinctUntilChanged().flatMapLatest { enabled ->
        if (enabled) flowOf(AppScanScope(true))
        else appBackupRepository.observeBackedUpApps().map { AppScanScope(false, it.toSet()) }
    }

    suspend fun selectDataItems(id: Long, apk: DataState, user: DataState, userDe: DataState, data: DataState, obb: DataState, media: DataState) {
        appsDao.selectDataItemsById(id, apk.name, user.name, userDe.name, data.name, obb.name, media.name)
    }

    suspend fun setBlocked(ids: Collection<Long>, blocked: Boolean) = appsDao.setBlocked(ids, blocked)

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        appsDao.setEnabled(id, enabled)
    }

    suspend fun deleteSelected(ids: List<Long>) =
        ids.count { id -> appsDao.queryById(id)?.let { appBackupRepository.deleteBackup(it) } == true }

    suspend fun setDataItems(ids: Collection<Long>, selections: PackageDataStates) {
        appsDao.updatePackageDataStates(ids.map { PackageDataStatesEntity(it, selections) })
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps.
     *
     * Faster than [fastInitialize] if there are too many newly installed apps.
     */
    suspend fun fullInitialize(onInit: suspend (cur: Int, max: Int, content: String) -> Unit) = appInitializationMutex.withLock {
        val scope = scanScope.first()
        val settings = settingsDataRepo.settingsData.first()
        val pm = context.packageManager
        val installedPackagesByUser = installedPackagesByUser()
        val itemCount = installedPackagesByUser.values.sumOf { it.size }
        var current = 0
        for ((userId, installedPackages) in installedPackagesByUser) {
            val storedSet = appsDao.queryPkgSetByUserId(OpType.BACKUP, userId).toSet()

            // Remove uninstalled apps
            val outdatedPackages = storedSet.subtract(installedPackages.map { it.packageName }.toSet())
            appsDao.deleteByPkgNames(opType = OpType.BACKUP, userId = userId, packageNames = outdatedPackages)

            val apps = mutableListOf<PackageEntity>()
            installedPackages.forEach { info ->
                onInit(current++, itemCount, info.packageName)
                if (storedSet.contains(info.packageName).not()) {
                    val isSystemApp = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (scope.includes(info.packageName, userId, isSystemApp)) {
                        apps.add(initializeApp(settings, pm, userId, info))
                        if (apps.size == APP_INIT_BATCH_SIZE) {
                            appsDao.upsert(apps.toList())
                            apps.clear()
                        }
                    }
                }
            }
            if (apps.isNotEmpty()) appsDao.upsert(apps)
            appBackupRepository.syncInstalledApps(userId)
        }
        labelsRepo.deleteOrphanedAppRefs()
    }

    suspend fun fastInitialize(onInit: suspend (cur: Int, max: Int, content: String) -> Unit) = appInitializationMutex.withLock {
        val scope = scanScope.first()
        val settings = settingsDataRepo.settingsData.first()
        val pm = context.packageManager
        val batches = installedPackagesByUser().map { (userId, packages) ->
            val installedPackages = packages.associateBy { it.packageName }
            val storedApps = appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, userId)
            val storedPackages = storedApps.associateBy { it.packageName }
            val scanned = installedPackages.filterValues { info ->
                val isSystem = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                scope.includes(info.packageName, userId, isSystem)
            }
            val changedApps = storedApps.filter { app ->
                val info = scanned[app.packageName] ?: return@filter false
                app.packageInfo.versionCode != PackageInfoCompat.getLongVersionCode(info) ||
                    app.packageInfo.versionName != info.versionName.orEmpty() ||
                    app.packageInfo.firstInstallTime != info.firstInstallTime ||
                    app.packageInfo.lastUpdateTime != info.lastUpdateTime ||
                    app.extraInfo.uid != (info.applicationInfo?.uid ?: -1) ||
                    app.extraInfo.enabled != (info.applicationInfo?.enabled ?: false)
            }
            FastInitializationBatch(
                userId = userId,
                outdatedPackages = storedPackages.keys.subtract(installedPackages.keys),
                missingPackages = scanned.filterKeys { it !in storedPackages }.values.toList(),
                changedApps = changedApps,
            )
        }
        val itemCount = batches.sumOf { it.missingPackages.size + it.changedApps.size }
        var current = 0
        for (batch in batches) {
            appsDao.deleteByPkgNames(OpType.BACKUP, batch.userId, batch.outdatedPackages)
            val apps = mutableListOf<PackageEntity>()
            batch.missingPackages.forEach { info ->
                onInit(current++, itemCount, info.packageName)
                apps.add(initializeApp(settings, pm, batch.userId, info))
                if (apps.size == APP_INIT_BATCH_SIZE) {
                    appsDao.upsert(apps.toList())
                    apps.clear()
                }
            }
            if (apps.isNotEmpty()) appsDao.upsert(apps)

            val updates = batch.changedApps.mapNotNull { app ->
                onInit(current++, itemCount, app.packageName)
                updateApp(pm, app)
            }
            appsDao.update(updates)
            appBackupRepository.syncInstalledApps(batch.userId)
        }
        labelsRepo.deleteOrphanedAppRefs()
    }

    private suspend fun initializeApp(settings: SettingsData, pm: PackageManager, userId: Int, info: android.content.pm.PackageInfo): PackageEntity {
        return PackageEntity(
            id = 0,
            indexInfo = PackageIndexInfo(
                opType = OpType.BACKUP,
                packageName = info.packageName,
                userId = userId,
                compressionType = settings.compressionType,
                preserveId = DefaultPreserveId,
                cloud = "",
                backupDir = "",
            ),
            packageInfo = info.toAppMetadata(pm),
            extraInfo = PackageExtraInfo(
                uid = info.applicationInfo?.uid ?: -1,
                hasKeystore = false,
                permissions = listOf(),
                ssaid = "",
                lastBackupTime = 0L,
                blocked = false,
                activated = false,
                firstUpdated = false,
                enabled = info.applicationInfo?.enabled ?: false,
            ),
            dataStates = PackageDataStates(),
            storageStats = PackageStorageStats(),
            dataStats = PackageDataStats(),
            displayStats = PackageDataStats(),
        )
    }

    suspend fun fullUpdate(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val pm = context.packageManager
        val scope = scanScope.first()
        val appsByUser = rootService.getUsers().associate { userInfo ->
            userInfo.id to appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, userInfo.id).filter { scope.includes(it.packageName, it.userId, it.isSystemApp) }
        }
        val itemCount = appsByUser.values.sumOf { it.size }
        var current = 0
        for ((userId, apps) in appsByUser) {
            val updateList = mutableListOf<PackageUpdateEntity>()

            apps.forEach { pkg ->
                onUpdate(current++, itemCount, pkg.packageName)
                val updateEntity = updateApp(pm, pkg)
                if (updateEntity != null) {
                    updateList.add(updateEntity)
                }
            }
            appsDao.update(updateList)
            appBackupRepository.syncInstalledApps(userId)
        }
    }

    suspend fun fastUpdate(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val pm = context.packageManager
        val scope = scanScope.first()
        val apps = appsDao.queryFirstUpdatedApps(opType = OpType.BACKUP, firstUpdated = false)
            .filter { scope.includes(it.packageName, it.userId, it.isSystemApp) }
        val updatedUsers = mutableSetOf<Int>()
        val updateList = mutableListOf<PackageUpdateEntity>()
        apps.forEachIndexed { index, pkg ->
            onUpdate(index, apps.size, pkg.packageName)
            val updateEntity = updateApp(pm, pkg)
            if (updateEntity != null) {
                updateList.add(updateEntity)
                updatedUsers.add(pkg.userId)
            }
        }
        appsDao.update(updateList)
        updatedUsers.forEach { appBackupRepository.syncInstalledApps(it) }
    }

    suspend fun updateLocalAppSizes(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val scope = scanScope.first()
        val installedApps = appsDao.queryUserIds(OpType.BACKUP)
            .flatMap { appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, it) }
            .filter { scope.includes(it.packageName, it.userId, it.isSystemApp) }
        installedApps.forEachIndexed { index, app ->
            calculateLocalAppSize(app)
            onUpdate(index, installedApps.size, app.packageName)
        }
    }

    suspend fun updateApp(pkg: PackageEntity) {
        val updateEntity = updateApp(context.packageManager, pkg)
        if (updateEntity != null) {
            appsDao.update(updateEntity)
            appBackupRepository.syncInstalledApps(pkg.userId, pkg.packageName)
        }
    }

    suspend fun refreshPermissions(app: PackageEntity): List<PackagePermission>? {
        val info = rootService.getPackageInfoAsUser(app.packageName, PackageManager.GET_PERMISSIONS, app.userId) ?: return null
        val permissions = rootService.getPermissions(info) ?: return null
        appsDao.updatePermissions(PackagePermissionsEntity(app.id, permissions))
        return permissions
    }

    suspend fun removeUninstalledApp(packageName: String, userId: Int) {
        appsDao.deleteByPkgNames(OpType.BACKUP, userId, setOf(packageName))
        appBackupRepository.syncInstalledApps(userId, packageName)
        labelsRepo.deleteOrphanedAppRefs()
    }

    private suspend fun updateApp(pm: PackageManager, pkg: PackageEntity): PackageUpdateEntity? {
        val info = rootService.getPackageInfoAsUser(
            pkg.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA,
            pkg.userId,
        ) ?: return null
        val uid = info.applicationInfo?.uid ?: -1
        return PackageUpdateEntity(
            id = pkg.id,
            packageInfo = info.toAppMetadata(pm),
            uid = uid,
            permissions = rootService.getPermissions(info) ?: pkg.extraInfo.permissions.takeIf { uid == pkg.extraInfo.uid }.orEmpty(),
            hasKeystore = PackageUtil.hasKeystore(context.readCustomSUFile().first(), uid, pkg.extraInfo),
            ssaid = rootService.getPackageSsaidAsUser(packageName = info.packageName, uid = uid, userId = pkg.userId),
            firstUpdated = true,
            enabled = info.applicationInfo?.enabled ?: false,
        )
    }

    private suspend fun android.content.pm.PackageInfo.toAppMetadata(pm: PackageManager) = PackageInfo(
        label = applicationInfo?.loadLabel(pm)?.toString() ?: packageName,
        versionName = versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(this),
        flags = applicationInfo?.flags ?: 0,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        isXposedModule = xposedModuleDetector.isModule(this),
    )

    private suspend fun activeUserIds() = rootService.getUsers().map { it.id }.also {
        check(it.isNotEmpty()) { "Unable to list Android users" }
    }

    suspend fun withInstalledBlacklistApps(items: List<BlacklistAppItem>, apply: suspend (List<PackageEntity>) -> Unit): Int {
        if (items.isEmpty()) {
            apply(emptyList())
            return 0
        }
        require(items.all { PathUtil.isValidPackageName(it.packageName) && it.userId >= 0 }) { "Invalid blacklist application" }
        val requested = items.distinct()
        return appInitializationMutex.withLock {
            val active = activeUserIds().toSet()
            val settings = settingsDataRepo.settingsData.first()
            val installed = buildList {
                for ((userId, group) in requested.groupBy { it.userId }) {
                    if (userId !in active) continue
                    val names = group.mapTo(hashSetOf()) { it.packageName }
                    for (info in getInstalledPackages(userId)) if (info.packageName in names) {
                        add(appsDao.query(info.packageName, OpType.BACKUP, userId)
                            ?: initializeApp(settings, context.packageManager, userId, info))
                    }
                }
            }
            apply(installed)
            requested.size - installed.size
        }
    }

    private suspend fun installedPackagesByUser(): Map<Int, List<android.content.pm.PackageInfo>> {
        val installed = activeUserIds().associateWith { getInstalledPackages(it) }.toMutableMap()
        for (userId in appsDao.getCachedInstalledUserIds(OpType.BACKUP)) installed.putIfAbsent(userId, emptyList())
        return installed
    }

    private suspend fun getInstalledPackages(userId: Int) =
        rootService.getInstalledPackagesAsUser(PackageManager.GET_META_DATA, userId).getOrThrow()
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }

    suspend fun load(cloudName: String, backupDir: String, onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) {
        if (cloudName.isEmpty()) {
            appBackupRepository.rebuildLocalIndex(backupDir) { completed, total, _ ->
                if (completed > 0) onLoad(completed - 1, total, "")
            }
            return
        }
        cloudRepo.withClient(cloudName) { client, _ ->
            val root = pathUtil.getCloudRemoteAppsDir(backupDir)
            if (!client.exists(root)) return@withClient
            val existing = appsDao.queryPackages(OpType.RESTORE, cloudName, backupDir)
            val known = existing.mapTo(hashSetOf()) { it.archivesRelativeDir }
            val configs = client.walkFileTree(root).filter { it.pathList.lastOrNull() == ConfigsPackageRestoreName }
            val additions = configs.mapIndexedNotNull { index, entry ->
                onLoad(index, configs.size, entry.pathString)
                val config = cloudRepo.readBackupConfig(client, entry.pathString, PackageRestoreConfig::class.java)
                val relativeDir = PathUtil.getPackageArchiveRelativeDir(config.packageName, config.userId, config.createdAt)
                check(config.schemaVersion == PACKAGE_RESTORE_CONFIG_SCHEMA_VERSION && relativeDir != null &&
                    entry.pathString == "$root/$relativeDir/$ConfigsPackageRestoreName") { "Invalid backup config: ${entry.pathString}" }
                if (relativeDir in known) null else config.toPackageEntity(cloudName, backupDir)
            }
            val missing = existing.filter {
                val relativeDir = PathUtil.getPackageArchiveRelativeDir(it.packageName, it.userId, it.preserveId)
                relativeDir != null && !client.exists("$root/$relativeDir")
            }.map { it.id }
            appsDao.applyRestoreScan(additions, missing)
        }
    }

    suspend fun calculateLocalAppSize(app: PackageEntity) {
        appsDao.updateDisplayStats(
            PackageDisplayStatsEntity(
                id = app.id,
                displayStats = calculateLocalAppDataStats(app),
            )
        )
    }

    suspend fun calculateLocalAppDataStats(app: PackageEntity) = PackageDataStats(
        apkBytes = calculateLocalAppDataSize(app, DataType.PACKAGE_APK),
        userBytes = calculateLocalAppDataSize(app, DataType.PACKAGE_USER),
        userDeBytes = calculateLocalAppDataSize(app, DataType.PACKAGE_USER_DE),
        dataBytes = calculateLocalAppDataSize(app, DataType.PACKAGE_DATA),
        obbBytes = calculateLocalAppDataSize(app, DataType.PACKAGE_OBB),
        mediaBytes = calculateLocalAppDataSize(app, DataType.PACKAGE_MEDIA),
    )

    private suspend fun calculateLocalAppDataSize(p: PackageEntity, dataType: DataType): Long {
        if (dataType == DataType.PACKAGE_APK) {
            return rootService.getPackageSourceDir(p.packageName, p.userId).sumOf { rootService.calculateSize(it) }
        }
        val src = "${dataType.srcDir(p.userId)}/${p.packageName}"
        val exclusions = dataType.archiveExclusions(p.packageName)
        return rootService.calculateSize(src, exclusions.children, exclusions.namePrefixes)
    }

    suspend fun calculateLocalAppArchiveSize(app: PackageEntity) {
        val relativeDir = PathUtil.getPackageArchiveRelativeDir(app.packageName, app.userId, app.preserveId) ?: return
        val dir = "${pathUtil.getLocalBackupAppsDir(app.indexInfo.backupDir)}/$relativeDir"
        val stats = appBackupRepository.calculateDisplayStats(dir, app.indexInfo.compressionType)
        packageRepo.updateDisplayStats(app.id, stats)
    }

    suspend fun protectApp(cloudName: String?, app: PackageEntity) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                protectCloudApp(this, app)
            }
        } else {
            protectLocalApp(app)
        }
    }

    private suspend fun protectLocalApp(app: PackageEntity) {
        val protectedApp = app.copy(indexInfo = app.indexInfo.copy(preserveId = DateUtil.getTimestamp()))
        val appsDir = pathUtil.getLocalBackupAppsDir(app.indexInfo.backupDir)
        val src = "${appsDir}/${app.archivesRelativeDir}"
        val dst = "${appsDir}/${protectedApp.archivesRelativeDir}"
        rootService.writeJson(data = protectedApp.toRestoreConfig(), dst = PathUtil.getPackageRestoreConfigDst(src))
        rootService.renameTo(src, dst)
        appsDao.update(protectedApp)
    }

    private suspend fun protectCloudApp(cloudName: String, app: PackageEntity) = runCatching {
        cloudRepo.withClient(cloudName) { client, _ ->
            val protectedApp = app.copy(indexInfo = app.indexInfo.copy(preserveId = DateUtil.getTimestamp()))
            val remote = app.indexInfo.backupDir
            val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(remote)
            val src = "${remoteAppsDir}/${app.archivesRelativeDir}"
            val dst = "${remoteAppsDir}/${protectedApp.archivesRelativeDir}"
            val tmpDir = pathUtil.newCloudTmpDir()
            val tmpJsonPath = PathUtil.getPackageRestoreConfigDst(tmpDir)
            rootService.writeJson(data = protectedApp.toRestoreConfig(), dst = tmpJsonPath)
            cloudRepo.upload(client = client, src = tmpJsonPath, dstDir = src)
            rootService.deleteRecursively(tmpDir)
            client.renameTo(src, dst)
        }
    }.withLog()
}
