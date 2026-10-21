package com.xayah.core.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.widget.Toast
import com.xayah.core.data.R
import com.xayah.core.data.util.srcDir
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.datastore.di.DbDispatchers.Default
import com.xayah.core.datastore.di.Dispatcher
import com.xayah.core.datastore.readCustomSUFile
import com.xayah.core.datastore.readLoadSystemApps
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.App
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.DefaultPreserveId
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.SettingsData
import com.xayah.core.model.UserInfo
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStatesEntity
import com.xayah.core.model.database.PackageDisplayStatsEntity
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.PackageUpdateEntity
import com.xayah.core.model.database.asExternalModel
import com.xayah.core.model.toPackageEntity
import com.xayah.core.model.toRestoreConfig
import com.xayah.core.rootservice.parcelables.PathParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.ConfigsPackageRestoreName
import com.xayah.core.util.DateUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.PackageUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.withLog
import com.xayah.core.util.withMainContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

private const val APP_INIT_BATCH_SIZE = 50
private val appInitializationMutex = Mutex()

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
            set.addAll(rootService.getInstalledPackagesAsUser(0, it.id).map { p -> "${p.packageName}-${it.id}" }.toSet())
        }
        set
    }

    fun getApp(id: Long) = appsDao.queryPackageFlow(id).flowOn(defaultDispatcher)

    fun getApp(packageName: String, userId: Int) =
        appsDao.queryFlow(packageName, OpType.BACKUP, userId, DefaultPreserveId)
            .flowOn(defaultDispatcher)

    fun getRestoreApps(): Flow<List<PackageEntity>> =
        packageRepo.queryPackagesFlow(OpType.RESTORE, blocked = false).flowOn(defaultDispatcher)

    fun getApps(
        opType: OpType,
        listData: Flow<ListData>,
        pkgUserSet: Flow<Set<String>>,
        refs: Flow<List<LabelAppCrossRefEntity>>,
        labelFilters: Flow<Map<String, LabelFilterMode>>,
        cloudName: String,
        backupDir: String
    ): Flow<List<App>> = combine(
        listData,
        pkgUserSet,
        refs,
        labelFilters,
        when (opType) {
            OpType.BACKUP -> appsDao.queryPackagesFlow(opType = opType, blocked = false)
            OpType.RESTORE -> appsDao.queryPackagesFlow(opType = opType, cloud = cloudName, backupDir = backupDir)
        }
    ) { lData, pSet, lRefs, filters, apps ->
        val data = lData.castTo<ListData.Apps>()
        val labelsByApp = lRefs.groupBy { Triple(it.packageName, it.userId, it.preserveId) }
            .mapValues { (_, refs) -> refs.mapTo(mutableSetOf()) { it.label } }
        val included = filters.filterValues { it == LabelFilterMode.INCLUDE }.keys
        val excluded = filters.filterValues { it == LabelFilterMode.EXCLUDE }.keys
        apps.asSequence()
            .filter { app ->
                if (app.isSystemApp) data.filters.systemApps else data.filters.nonSystemApps
            }
            .filter { app ->
                if (app.extraInfo.enabled) data.filters.unfrozenApps else data.filters.frozenApps
            }
            .filter { app -> data.filters.matchesXposed(app.packageInfo.isXposedModule) }
            .filter {
                if (opType == OpType.BACKUP) data.filters.installedApps
                else packageRepo.getInstalledPredicate(value = data.filters.installedApps, pkgUserSet = pSet)(it)
            }
            .filter {
                if (opType == OpType.BACKUP) true
                else packageRepo.getNotInstalledPredicate(value = data.filters.notInstalledApps, pkgUserSet = pSet)(it)
            }
            .filter(packageRepo.getUserIdPredicateNew(userId = data.userList.getOrNull(data.userIndex)?.id))
            .filter { app ->
                val appLabels = labelsByApp[Triple(app.packageName, app.userId, app.preserveId)].orEmpty()
                data.filters.matchesIncludedLabels(appLabels, included) && appLabels.none(excluded::contains)
            }
            .toList()
            .map(PackageEntity::asExternalModel)
    }.flowOn(defaultDispatcher)

    fun countApps(opType: OpType) = appsDao.countPackagesFlow(opType = opType, blocked = false)
    fun countSelectedApps(opType: OpType) = appsDao.countActivatedPackagesFlow(opType = opType, blocked = false)

    suspend fun getLoadSystemApps() = context.readLoadSystemApps().first()

    suspend fun selectApp(id: Long, selected: Boolean) {
        appsDao.activateById(id, selected)
    }

    suspend fun replaceSelection(opType: OpType, ids: Collection<Long>) {
        appsDao.clearActivated(opType)
        appsDao.activateByIds(ids.toList(), true)
    }

    suspend fun selectDataItems(id: Long, apk: DataState, user: DataState, userDe: DataState, data: DataState, obb: DataState, media: DataState) {
        appsDao.selectDataItemsById(id, apk.name, user.name, userDe.name, data.name, obb.name, media.name)
    }

    suspend fun selectAll(ids: List<Long>) {
        appsDao.activateByIds(ids, true)
    }

    suspend fun unselectAll(ids: List<Long>) {
        appsDao.activateByIds(ids, false)
    }

    suspend fun reverseAll(ids: List<Long>) {
        appsDao.reverseActivatedByIds(ids)
    }

    suspend fun blockSelected(ids: List<Long>) {
        appsDao.blockByIds(ids)
    }

    suspend fun blockByIds(ids: List<Long>) {
        appsDao.blockByIds(ids)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        appsDao.setEnabled(id, enabled)
    }

    suspend fun deleteSelected(ids: List<Long>) {
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val deletedIds = mutableListOf<Long>()
        ids.forEach {
            val app = appsDao.queryById(it)
            if (app != null) {
                val isSuccess = if (app.indexInfo.cloud.isEmpty()) {
                    val src = "${appsDir}/${app.archivesRelativeDir}"
                    rootService.deleteRecursively(src)
                } else {
                    runCatching {
                        cloudRepo.withClient(app.indexInfo.cloud) { client, entity ->
                            val remote = entity.remote
                            val remoteArchivesPackagesDir = pathUtil.getCloudRemoteAppsDir(remote)
                            val src = "${remoteArchivesPackagesDir}/${app.archivesRelativeDir}"
                            if (client.exists(src)) client.deleteRecursively(src)
                        }
                    }.withLog().isSuccess
                }
                if (isSuccess) deletedIds.add(app.id)
            }
        }
        appsDao.deleteByIds(deletedIds)
    }

    suspend fun setDataItems(ids: List<Long>, selections: PackageDataStates) {
        appsDao.updatePackageDataStates(ids.map { PackageDataStatesEntity(it, selections) })
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps.
     *
     * Faster than [fastInitialize] if there are too many newly installed apps.
     */
    suspend fun fullInitialize(onInit: suspend (cur: Int, max: Int, content: String) -> Unit) = appInitializationMutex.withLock {
        val loadSystemApps = context.readLoadSystemApps().first()
        val settings = settingsDataRepo.settingsData.first()
        val pm = context.packageManager
        val installedPackagesByUser = rootService.getUsers().associate { userInfo ->
            userInfo.id to getInstalledPackages(userInfo.id)
        }
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
                    if (loadSystemApps || isSystemApp.not()) {
                        apps.add(initializeApp(settings, pm, userId, info))
                        if (apps.size == APP_INIT_BATCH_SIZE) {
                            appsDao.upsert(apps.toList())
                            apps.clear()
                        }
                    }
                }
            }
            if (apps.isNotEmpty()) appsDao.upsert(apps)
            syncInstalledApps(userId)
        }
        labelsRepo.deleteOrphanedAppRefs()
    }

    /**
     * Initialize only newly installed apps or remove uninstalled apps.
     */
    suspend fun fastInitialize(onInit: suspend (cur: Int, max: Int, content: String) -> Unit) = appInitializationMutex.withLock {
        val loadSystemApps = context.readLoadSystemApps().first()
        val settings = settingsDataRepo.settingsData.first()
        val pm = context.packageManager
        val batches = rootService.getUsers().map { userInfo ->
            val userId = userInfo.id
            val installedPackages = getInstalledPackages(userId).associateBy { it.packageName }
            val storedApps = appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, userId)
            val storedPackages = storedApps.associateBy { it.packageName }
            val changedApps = storedApps.filter { app ->
                val info = installedPackages[app.packageName] ?: return@filter false
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                }
                app.packageInfo.versionCode != versionCode || app.packageInfo.lastUpdateTime != info.lastUpdateTime
            }
            FastInitializationBatch(
                userId = userId,
                outdatedPackages = storedPackages.keys.subtract(installedPackages.keys),
                missingPackages = installedPackages.filterKeys { it !in storedPackages }.values.toList(),
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
                val isSystemApp = ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                if (loadSystemApps || isSystemApp.not()) {
                    apps.add(initializeApp(settings, pm, batch.userId, info))
                    if (apps.size == APP_INIT_BATCH_SIZE) {
                        appsDao.upsert(apps.toList())
                        apps.clear()
                    }
                }
            }
            if (apps.isNotEmpty()) appsDao.upsert(apps)

            val userHandle = rootService.getUserHandle(batch.userId)
            val updates = batch.changedApps.mapNotNull { app ->
                onInit(current++, itemCount, app.packageName)
                updateApp(pm, app, batch.userId, userHandle)
            }
            appsDao.update(updates)
            syncInstalledApps(batch.userId)
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
            packageInfo = PackageInfo(
                info.applicationInfo?.loadLabel(pm).toString(),
                versionName = info.versionName ?: "",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                },
                flags = info.applicationInfo?.flags ?: 0,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
                isXposedModule = xposedModuleDetector.isModule(info),
            ),
            extraInfo = PackageExtraInfo(
                uid = info.applicationInfo?.uid ?: -1,
                hasKeystore = false,
                permissions = listOf(),
                ssaid = "",
                lastBackupTime = 0L,
                blocked = false,
                activated = false,
                firstUpdated = false,
                enabled = true,
            ),
            dataStates = PackageDataStates(),
            storageStats = PackageStorageStats(),
            dataStats = PackageDataStats(),
            displayStats = PackageDataStats(),
        )
    }

    suspend fun fullUpdate(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val pm = context.packageManager
        val appsByUser = rootService.getUsers().associate { userInfo ->
            userInfo.id to appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, userInfo.id)
        }
        val itemCount = appsByUser.values.sumOf { it.size }
        var current = 0
        for ((userId, apps) in appsByUser) {
            val userHandle = rootService.getUserHandle(userId)
            val updateList = mutableListOf<PackageUpdateEntity>()

            apps.forEach { pkg ->
                onUpdate(current++, itemCount, pkg.packageName)
                val updateEntity = updateApp(pm, pkg, userId, userHandle)
                if (updateEntity != null) {
                    updateList.add(updateEntity)
                }
            }
            appsDao.update(updateList)
            syncInstalledApps(userId)
        }
    }

    suspend fun fastUpdate(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val pm = context.packageManager
        val apps = appsDao.queryFirstUpdatedApps(opType = OpType.BACKUP, firstUpdated = false)
        val updateList = mutableListOf<PackageUpdateEntity>()
        apps.forEachIndexed { index, pkg ->
            onUpdate(index, apps.size, pkg.packageName)
            val userId = pkg.userId
            val userHandle = rootService.getUserHandle(userId)
            val updateEntity = updateApp(pm, pkg, userId, userHandle)
            if (updateEntity != null) {
                updateList.add(updateEntity)
            }
        }
        appsDao.update(updateList)
        appsDao.queryUserIds(OpType.BACKUP).forEach { syncInstalledApps(it) }
    }

    suspend fun updateLocalAppSizes(onUpdate: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val installedApps = appsDao.queryUserIds(OpType.BACKUP)
            .flatMap { appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, it) }
        installedApps.forEachIndexed { index, app ->
            onUpdate(index, installedApps.size, app.packageName)
            calculateLocalAppSize(app)
        }
    }

    private suspend fun syncInstalledApps(userId: Int) {
        appBackupRepository.syncInstalledApps(
            userId = userId,
            apps = appsDao.queryPkgEntitiesByUserId(OpType.BACKUP, userId),
        )
    }

    suspend fun updateApp(pkg: PackageEntity, userId: Int) {
        val pm = context.packageManager
        val userHandle = rootService.getUserHandle(userId)
        val updateEntity = updateApp(pm, pkg, userId, userHandle)
        if (updateEntity != null) {
            appsDao.update(updateEntity)
            syncInstalledApps(userId)
        }
    }

    suspend fun removeUninstalledApp(packageName: String, userId: Int) {
        appsDao.deleteByPkgNames(OpType.BACKUP, userId, setOf(packageName))
        syncInstalledApps(userId)
        labelsRepo.deleteOrphanedAppRefs()
    }

    private suspend fun updateApp(pm: PackageManager, pkg: PackageEntity, userId: Int, userHandle: UserHandle?): PackageUpdateEntity? {
        val info = rootService.getPackageInfoAsUser(
            pkg.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA,
            userId,
        )
        if (info == null) {
            appsDao.delete(pkg.id)
            return null
        }
        val uid = info.applicationInfo?.uid ?: -1
        val stats = userHandle?.let { rootService.queryStatsForPackage(info, it) }
        return PackageUpdateEntity(
            id = pkg.id,
            packageInfo = PackageInfo(
                label = info.applicationInfo?.loadLabel(pm).toString(),
                versionName = info.versionName ?: "",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                },
                flags = info.applicationInfo?.flags ?: 0,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
                isXposedModule = xposedModuleDetector.isModule(info),
            ),
            storageStats = stats?.let {
                PackageStorageStats(it.appBytes, it.cacheBytes, it.dataBytes, it.externalCacheBytes)
            } ?: pkg.storageStats,
            uid = uid,
            permissions = rootService.getPermissions(packageInfo = info),
            hasKeystore = PackageUtil.hasKeystore(context.readCustomSUFile().first(), uid),
            ssaid = rootService.getPackageSsaidAsUser(packageName = info.packageName, uid = uid, userId = userId),
            firstUpdated = true,
            enabled = info.applicationInfo?.enabled ?: false,
        )
    }

    private suspend fun getInstalledPackages(userId: Int) =
        rootService.getInstalledPackagesAsUser(PackageManager.GET_META_DATA, userId)
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }

    suspend fun load(cloudName: String?, onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                loadCloudApps(this, onLoad)
            }
        } else {
            loadLocalApps(onLoad)
        }
    }

    private fun parsePreserveAndUserId(pathParcelable: PathParcelable): Pair<Long, Int>? {
        runCatching {
            val userPath = pathParcelable.pathList[pathParcelable.pathList.size - 2]
            if (userPath.contains("@")) {
                val userIdWithPreserveId = userPath.split("@")
                val preserveId = userIdWithPreserveId.lastOrNull()?.toLongOrNull() ?: 0L
                val userId = userIdWithPreserveId.first().split("_").lastOrNull()?.toIntOrNull() ?: 0
                return preserveId to userId
            } else {
                // Main backup
                val preserveId = 0L
                val userId = userPath.split("_").lastOrNull()?.toIntOrNull() ?: 0
                return preserveId to userId
            }
        }
        return null
    }

    private suspend fun loadLocalApps(onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) {
        val path = pathUtil.getLocalBackupAppsDir()
        val paths = rootService.walkFileTree(path)
        paths.forEachIndexed { index, pathParcelable ->
            val fileName = PathUtil.getFileName(pathParcelable.pathString)
            onLoad(index, paths.size, fileName)
            if (fileName == ConfigsPackageRestoreName) {
                runCatching {
                    rootService.readJson<PackageRestoreConfig>(pathParcelable.pathString)
                        ?.toPackageEntity(cloud = "", backupDir = context.localBackupSaveDir())
                        ?.also { p ->
                            parsePreserveAndUserId(pathParcelable)?.let { (preserveId, userId) ->
                                p.indexInfo.preserveId = preserveId
                                p.indexInfo.userId = userId
                            }
                        }?.apply {
                        if (appsDao.query(packageName, indexInfo.opType, userId, preserveId, indexInfo.compressionType, indexInfo.cloud, indexInfo.backupDir) == null) {
                            appsDao.upsert(this)
                        }
                    }
                }
            }
        }
        rootService.clearEmptyDirectoriesRecursively(path)
        appsDao.queryPackages(OpType.RESTORE, "", context.localBackupSaveDir()).forEach {
            val src = "${path}/${it.archivesRelativeDir}"
            if (rootService.exists(src).not()) {
                appsDao.delete(it.id)
            }
        }
    }

    private suspend fun loadCloudApps(cloudName: String, onLoad: suspend (cur: Int, max: Int, content: String) -> Unit) = runCatching {
        cloudRepo.withClient(cloudName) { client, entity ->
            val remote = entity.remote
            val path = pathUtil.getCloudRemoteAppsDir(remote)
            if (client.exists(path)) {
                val paths = client.walkFileTree(path)
                val tmpDir = pathUtil.getCloudTmpDir()
                paths.forEachIndexed { index, pathParcelable ->
                    val fileName = PathUtil.getFileName(pathParcelable.pathString)
                    onLoad(index, paths.size, fileName)
                    if (fileName == ConfigsPackageRestoreName) {
                        runCatching {
                            cloudRepo.download(client = client, src = pathParcelable.pathString, dstDir = tmpDir) { path ->
                                rootService.readJson<PackageRestoreConfig>(path)
                                    ?.toPackageEntity(cloud = entity.name, backupDir = remote)
                                    ?.also { p ->
                                        parsePreserveAndUserId(pathParcelable)?.let { (preserveId, userId) ->
                                            p.indexInfo.preserveId = preserveId
                                            p.indexInfo.userId = userId
                                        }
                                    }?.apply {
                                    if (appsDao.query(packageName, indexInfo.opType, userId, preserveId, indexInfo.compressionType, indexInfo.cloud, indexInfo.backupDir) == null) {
                                        appsDao.upsert(this)
                                    }
                                }
                            }
                        }
                    }
                }
                appsDao.queryPackages(OpType.RESTORE, entity.name, entity.remote).forEach {
                    val src = "${path}/${it.archivesRelativeDir}"
                    if (client.exists(src).not()) {
                        appsDao.delete(it.id)
                    }
                }
            }
        }
    }.withLog()

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
        if (!rootService.exists(src)) return 0L
        val excluded = when (dataType) {
            DataType.PACKAGE_USER, DataType.PACKAGE_USER_DE ->
                listOf(".ota", "cache", "lib", "code_cache", "no_backup").map { "$src/$it" }
            DataType.PACKAGE_DATA, DataType.PACKAGE_OBB, DataType.PACKAGE_MEDIA ->
                rootService.listFilePaths(src).filter { path ->
                    val name = PathUtil.getFileName(path)
                    name == "cache" || name.startsWith("Backup_")
                }
            else -> emptyList()
        }
        return (rootService.calculateSize(src) - excluded.sumOf { rootService.calculateSize(it) }).coerceAtLeast(0L)
    }

    /**
     * @author <a href="https://github.com/MuntashirAkon">@MuntashirAkon</a>
     */
    suspend fun launchApp(packageName: String, userId: Int) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val user = rootService.getUserHandle(userId)
        if (launcherApps.isPackageEnabled(packageName, user).not()) {
            // Package not enabled
            withMainContext {
                Toast.makeText(context, context.getString(R.string.app_is_frozen), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val activityInfoList = launcherApps.getActivityList(packageName, user)
        if (activityInfoList.isEmpty()) {
            // No activities
            withMainContext {
                Toast.makeText(context, context.getString(R.string.no_activities_found), Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Return the first openable activity
        val info = activityInfoList[0]
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .setComponent(info.componentName)

        rootService.startActivity(intent, userId)
    }

    private fun getArchiveSrc(dstDir: String, dataType: DataType, ct: CompressionType) = "${dstDir}/${dataType.type}.${ct.suffix}"

    private suspend fun calculateLocalAppArchiveSize(p: PackageEntity, dataType: DataType) = rootService.calculateSize(
        getArchiveSrc("${pathUtil.getLocalBackupAppsDir()}/${p.archivesRelativeDir}", dataType, p.indexInfo.compressionType)
    )

    suspend fun calculateLocalAppArchiveSize(app: PackageEntity) {
        app.displayStats.apkBytes = calculateLocalAppArchiveSize(app, DataType.PACKAGE_APK)
        app.displayStats.userBytes = calculateLocalAppArchiveSize(app, DataType.PACKAGE_USER)
        app.displayStats.userDeBytes = calculateLocalAppArchiveSize(app, DataType.PACKAGE_USER_DE)
        app.displayStats.dataBytes = calculateLocalAppArchiveSize(app, DataType.PACKAGE_DATA)
        app.displayStats.obbBytes = calculateLocalAppArchiveSize(app, DataType.PACKAGE_OBB)
        app.displayStats.mediaBytes = calculateLocalAppArchiveSize(app, DataType.PACKAGE_MEDIA)
        appsDao.upsert(app)
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
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val src = "${appsDir}/${app.archivesRelativeDir}"
        val dst = "${appsDir}/${protectedApp.archivesRelativeDir}"
        rootService.writeJson(data = protectedApp.toRestoreConfig(), dst = PathUtil.getPackageRestoreConfigDst(src))
        rootService.renameTo(src, dst)
        appsDao.update(protectedApp)
    }

    private suspend fun protectCloudApp(cloudName: String, app: PackageEntity) = runCatching {
        cloudRepo.withClient(cloudName) { client, entity ->
            val protectedApp = app.copy(indexInfo = app.indexInfo.copy(preserveId = DateUtil.getTimestamp()))
            val remote = entity.remote
            val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(remote)
            val src = "${remoteAppsDir}/${app.archivesRelativeDir}"
            val dst = "${remoteAppsDir}/${protectedApp.archivesRelativeDir}"
            val tmpDir = pathUtil.getCloudTmpDir()
            val tmpJsonPath = PathUtil.getPackageRestoreConfigDst(tmpDir)
            rootService.writeJson(data = protectedApp.toRestoreConfig(), dst = tmpJsonPath)
            cloudRepo.upload(client = client, src = tmpJsonPath, dstDir = src)
            rootService.deleteRecursively(tmpDir)
            client.renameTo(src, dst)
        }
    }.withLog()

    suspend fun deleteApp(cloudName: String?, app: PackageEntity) {
        if (cloudName.isNullOrEmpty().not()) {
            cloudName?.apply {
                deleteCloudApp(this, app)
            }
        } else {
            deleteLocalApp(app)
        }
    }

    private suspend fun deleteLocalApp(app: PackageEntity) {
        val appsDir = pathUtil.getLocalBackupAppsDir()
        val src = "${appsDir}/${app.archivesRelativeDir}"
        if (rootService.deleteRecursively(src)) {
            appsDao.delete(app.id)
        }
    }

    private suspend fun deleteCloudApp(cloudName: String, app: PackageEntity) = runCatching {
        cloudRepo.withClient(cloudName) { client, entity ->
            val remote = entity.remote
            val remoteAppsDir = pathUtil.getCloudRemoteAppsDir(remote)
            val src = "${remoteAppsDir}/${app.archivesRelativeDir}"
            if (client.exists(src)) {
                client.deleteRecursively(src)
                if (client.exists(src).not()) {
                    appsDao.delete(app.id)
                }
            }
        }
    }.withLog()
}
