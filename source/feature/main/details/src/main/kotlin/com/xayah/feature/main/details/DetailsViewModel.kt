package com.xayah.feature.main.details

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.StrictMode
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.ui.component.PackageIconLoader
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.util.srcDir
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupAppEntity
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.DataType
import com.xayah.core.model.Target
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.ColoredLabel
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackagePermission
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.util.launchOnDefault
import com.xayah.core.util.PathUtil
import com.xayah.core.util.sharedFileUri
import com.xayah.feature.main.details.DetailsUiState.Error
import com.xayah.feature.main.details.DetailsUiState.Loading
import com.xayah.feature.main.details.DetailsUiState.Success
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import javax.inject.Inject

@HiltViewModel
class DetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val rootService: RemoteRootService,
    private val appsRepo: AppsRepo,
    private val appBackupRepository: AppBackupRepository,
    private val icons: PackageIconLoader,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
    private val listDataRepo: ListDataRepo,
) : ViewModel() {
    private val id: Long = savedStateHandle.get<String>(MainRoutes.ARG_ID)?.toLongOrNull() ?: 0L
    private val packageName = savedStateHandle.get<String>(MainRoutes.ARG_PACKAGE_NAME)
    private val userId = savedStateHandle.get<String>(MainRoutes.ARG_USER_ID)?.toIntOrNull() ?: 0
    private val target: Target = savedStateHandle.get<String>(MainRoutes.ARG_TARGET)
        ?.let { Target.valueOf(it) }
        ?: Target.Apps
    private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val appRuntimeInfo = MutableStateFlow(AppRuntimeInfo())
    private var shareJob: Job? = null
    private val refreshMutex = Mutex()
    private var initialRefreshRequested = false
    private val _furtherOperations = MutableStateFlow<FurtherOperationsUiState>(FurtherOperationsUiState.Idle)
    val furtherOperations = _furtherOperations.asStateFlow()
    private val _updatingPermission = MutableStateFlow<String?>(null)
    val updatingPermission = _updatingPermission.asStateFlow()
    private val _permissionStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionStates = _permissionStates.asStateFlow()

    val uiState: StateFlow<DetailsUiState> = when (target) {
        Target.Apps -> {
            val installedAppFlow = if (id != 0L) {
                appsRepo.getApp(id)
            } else {
                appsRepo.getApp(checkNotNull(packageName), userId)
            }
            val appFlow = if (packageName != null) {
                val key = AppKey(packageName, userId)
                combine(installedAppFlow, appBackupRepository.observeApp(packageName, userId), appsRepo.observeArchivedXposedApps()) { installed, indexed, modules ->
                    installed?.let { AppDetailsSource(it, true, indexed?.note.orEmpty()) }
                        ?: indexed?.let { AppDetailsSource(it.toPackageEntity(key in modules), false, it.note) }
                }
            } else {
                installedAppFlow.map { app -> app?.let { AppDetailsSource(it, true) } }
            }
            viewModelScope.launchOnDefault {
                appFlow
                    .filterNotNull()
                    .filter { it.isInstalled }
                    .distinctUntilChangedBy { Triple(it.app.packageName, it.app.userId, it.app.packageInfo.versionCode) }
                    .collect { loadAppRuntimeInfo(it.app) }
            }
            combine(appFlow, isRefreshing, labelsRepo.getColoredLabelsFlow(), labelsRepo.getAppRefsFlow(), appRuntimeInfo) { source, isRefreshing, labels, refs, runtimeInfo ->
                if (source != null) {
                    val app = source.app
                    Success.App(
                        isRefreshing = isRefreshing,
                        labels = labels,
                        app = app,
                        refs = refs.filter { ref ->
                            ref.packageName == app.packageName && ref.userId == app.userId
                        },
                        architecture = runtimeInfo.architecture,
                        targetSdk = runtimeInfo.targetSdk,
                        isInstalled = source.isInstalled,
                        note = source.note,
                    )
                } else {
                    Error
                }
            }
        }

        Target.Files -> {
            combine(filesRepo.getFile(id), isRefreshing, labelsRepo.getColoredLabelsFlow(), labelsRepo.getFileRefsFlow()) { file, isRefreshing, labels, refs ->
                if (file != null) {
                    Success.File(isRefreshing = isRefreshing, labels = labels, file = file, refs = refs.filter { ref ->
                        labels.find { it.label == ref.label } != null && ref.path == file.path && ref.preserveId == file.preserveId
                    })
                } else {
                    Error
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        initialValue = Loading,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    fun refresh(initial: Boolean = false) {
        if (initial && initialRefreshRequested) return
        if (initial) initialRefreshRequested = true
        viewModelScope.launchOnDefault {
            refreshMutex.withLock {
                val state = uiState.first { it !is Loading }
                if (state !is Success) return@withLock
                isRefreshing.emit(true)
                try {
                    when (state) {
                        is Success.App -> {
                            if (!state.isInstalled) return@withLock
                            when (state.app.indexInfo.opType) {
                                OpType.BACKUP -> {
                                    appsRepo.updateApp(state.app, state.app.userId)
                                    appsRepo.calculateLocalAppSize(state.app)
                                }

                                OpType.RESTORE -> {
                                    appsRepo.calculateLocalAppArchiveSize(state.app)
                                }
                            }
                        }

                        is Success.File -> {
                            when (state.file.indexInfo.opType) {
                                OpType.BACKUP -> {
                                    filesRepo.calculateLocalFileSize(state.file)
                                }

                                OpType.RESTORE -> {
                                    filesRepo.calculateLocalFileArchiveSize(state.file)
                                }
                            }
                        }
                    }
                } finally {
                    isRefreshing.emit(false)
                }
            }
        }
    }

    fun setDataStates(id: Long, dataStates: PackageDataStates) {
        viewModelScope.launchOnDefault {
            appsRepo.setDataItems(listOf(id), dataStates)
        }
    }

    fun addLabel(label: String) {
        viewModelScope.launchOnDefault {
            val normalized = label.trim()
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (normalized.isEmpty()) return@launchOnDefault
            labelsRepo.addLabel(normalized)
            labelsRepo.addLabelAppCrossRef(
                LabelAppCrossRefEntity(
                    label = normalized,
                    packageName = app.packageName,
                    userId = app.userId,
                )
            )
        }
    }

    fun deleteLabel(label: String) {
        viewModelScope.launchOnDefault {
            labelsRepo.deleteLabel(label)
            listDataRepo.onLabelChanged(label, null)
        }
    }

    fun updateAppNote(note: String) {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            appBackupRepository.updateAppNote(app.packageName, app.userId, note)
            showToast(R.string.note_saved)
        }
    }

    fun updateLabel(oldLabel: String, newLabel: String, colorArgb: Long, allowMerge: Boolean) {
        viewModelScope.launchOnDefault {
            if (!labelsRepo.updateLabel(oldLabel, newLabel, colorArgb, allowMerge)) {
                showToast(R.string.label_changed)
            } else if (oldLabel != newLabel) {
                listDataRepo.onLabelChanged(oldLabel, newLabel)
            }
        }
    }

    fun selectAppLabel(selected: Boolean, ref: LabelAppCrossRefEntity?) {
        viewModelScope.launchOnDefault {
            if (ref != null) {
                if (selected) {
                    labelsRepo.deleteLabelAppCrossRef(ref)
                } else {
                    labelsRepo.addLabelAppCrossRef(ref)
                }
            }
        }
    }

    fun selectFileLabel(selected: Boolean, ref: LabelFileCrossRefEntity?) {
        viewModelScope.launchOnDefault {
            if (ref != null) {
                if (selected) {
                    labelsRepo.deleteLabelFileCrossRef(ref)
                } else {
                    labelsRepo.addLabelFileCrossRef(ref)
                }
            }
        }
    }

    fun setBlocked(blocked: Boolean) {
        viewModelScope.launchOnDefault {
            val updated = when (val state = uiState.value) {
                is Success.App -> appsRepo.setBlocked(listOf(state.app.id), blocked)
                is Success.File -> filesRepo.setBlocked(listOf(state.file.id), blocked)
                else -> return@launchOnDefault
            }
            showToast(when {
                updated == 0 -> R.string.failed
                blocked -> R.string.added_to_blacklist
                else -> R.string.removed_from_blacklist
            })
        }
    }

    fun setFrozen(frozen: Boolean) {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            val state = if (frozen) PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val changed = rootService.setApplicationEnabledSetting(app.packageName, state, 0, app.userId, null)
            if (changed.isFailure || rootService.getApplicationEnabledSetting(app.packageName, app.userId) != state) {
                showToast(R.string.failed)
                return@launchOnDefault
            }
            appsRepo.setEnabled(app.id, !frozen)
            showToast(if (frozen) R.string.app_frozen else R.string.app_unfrozen)
        }
    }

    fun launchApp() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (!rootService.startActivity(intent, app.userId)) {
                showToast(if (app.extraInfo.enabled) R.string.external_action_failed else com.xayah.core.data.R.string.app_is_frozen)
            }
        }
    }

    fun forceStopApp() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            val message = when (rootService.forceStopPackageIfRunning(app.packageName, app.userId)) {
                true -> R.string.app_force_stopped
                false -> R.string.app_not_running
                null -> R.string.force_stop_failed
            }
            showToast(message)
        }
    }

    fun uninstallApp(keepData: Boolean) {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (!rootService.uninstallPackageAsUser(app.packageName, app.userId, keepData)) {
                showToast(R.string.app_uninstall_failed)
                return@launchOnDefault
            }
            appsRepo.removeUninstalledApp(app.packageName, app.userId)
            showToast(if (keepData) R.string.app_uninstalled_data_kept else R.string.app_uninstalled)
        }
    }

    fun clearAppData() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (rootService.clearPackageDataAsUser(app.packageName, app.userId)) {
                showToast(R.string.app_data_cleared)
                appsRepo.calculateLocalAppSize(app)
            } else showToast(R.string.app_clear_data_failed)
        }
    }

    fun clearAppCache() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (rootService.clearPackageCacheAsUser(app.packageName, app.userId)) {
                showToast(R.string.app_cache_cleared)
                appsRepo.calculateLocalAppSize(app)
            } else showToast(R.string.app_clear_cache_failed)
        }
    }

    fun openAppSettings() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", app.packageName, null))
            if (!rootService.startActivity(intent, app.userId)) showToast(R.string.external_action_failed)
        }
    }

    fun resolveDataPath(dataType: DataType, onResolved: (String) -> Unit) {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            val path = if (dataType == DataType.PACKAGE_APK) {
                rootService.getPackageSourceDir(app.packageName, app.userId).firstOrNull()
            } else {
                "${dataType.srcDir(app.userId)}/${app.packageName}"
            }
            if (path == null) {
                showToast(context.getString(R.string.path_does_not_exist, app.packageName))
                return@launchOnDefault
            }
            if (rootService.exists(path).not()) {
                showToast(context.getString(R.string.path_does_not_exist, path))
                return@launchOnDefault
            }
            withContext(Dispatchers.Main.immediate) {
                onResolved(path)
            }
        }
    }

    fun copyPath(path: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.packageName, path))
        Toast.makeText(context, context.getString(R.string.path_copied, path), Toast.LENGTH_SHORT).show()
    }

    fun copyAppName() {
        val app = (uiState.value as? Success.App)?.app ?: return
        copyText(app.packageInfo.label, app.packageInfo.label, R.string.app_name_copied)
    }

    fun copyPackageName() {
        val app = (uiState.value as? Success.App)?.app ?: return
        copyText(app.packageInfo.label, app.packageName, R.string.package_name_copied)
    }

    private fun copyText(label: String, text: String, @StringRes message: Int) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun openPath(path: String) {
        allowFileUriExposure()
        val file = File(path)
        val mimeType = if (file.extension.equals("apk", ignoreCase = true)) APK_MIME_TYPE else "resource/folder"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.fromFile(file), mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val chooser = Intent.createChooser(intent, context.getString(R.string.open_path)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(chooser) }.isFailure) {
            viewModelScope.launch { showToast(R.string.external_action_failed) }
        }
    }

    fun setPermission(permission: PackagePermission, granted: Boolean) {
        if (_updatingPermission.value != null) return
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            _updatingPermission.value = permission.name
            try {
                val user = rootService.getUserHandle(app.userId)
                if (user == null) {
                    showToast(R.string.permission_update_failed)
                    return@launchOnDefault
                }
                if (granted) {
                    rootService.grantRuntimePermission(app.packageName, permission.name, user)
                } else {
                    rootService.revokeRuntimePermission(app.packageName, permission.name, user)
                }
                if (permission.op != android.app.AppOpsManagerHidden.OP_NONE) {
                    rootService.setOpsMode(
                        permission.op,
                        app.extraInfo.uid,
                        app.packageName,
                        if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED,
                    )
                }
                appsRepo.updateApp(app, app.userId)
                val packageInfo = rootService.getPackageInfoAsUser(
                    app.packageName,
                    PackageManager.GET_PERMISSIONS,
                    app.userId,
                )
                val updated = if (packageInfo == null) {
                    null
                } else {
                    rootService.getPermissions(packageInfo).firstOrNull { it.name == permission.name }
                }
                val applied = updated != null && (updated.isGranted || updated.isOpsAllowed) == granted
                if (updated != null) {
                    _permissionStates.value = _permissionStates.value +
                        (permission.name to (updated.isGranted || updated.isOpsAllowed))
                }
                showToast(
                    when {
                        !applied -> R.string.permission_update_failed
                        granted -> R.string.permission_granted
                        else -> R.string.permission_revoked
                    }
                )
            } finally {
                _updatingPermission.value = null
            }
        }
    }

    fun saveAppIcon() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            val icon = icons.load(app.packageName, app.userId, refresh = true)
            val saved = icon != null && context.exportAppIcon(icon.toBitmap(512, 512, Bitmap.Config.ARGB_8888), app.packageName)
            showToast(if (saved) R.string.app_icon_saved else R.string.app_icon_save_failed)
        }
    }

    fun shareApk() {
        if (shareJob?.isActive == true) return
        shareJob = viewModelScope.launch(Dispatchers.IO) {
            val app = (uiState.value as? Success.App)?.app ?: return@launch
            val file = prepareAppShareFile(context.externalCacheDir ?: context.cacheDir, app, rootService)
            if (file == null) {
                showToast(R.string.share_apk_failed)
                return@launch
            }
            var launched = false
            try {
                withContext(Dispatchers.Main.immediate) {
                    launched = runCatching {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = if (file.extension == "apk") APK_MIME_TYPE else BINARY_MIME_TYPE
                            putExtra(Intent.EXTRA_STREAM, context.sharedFileUri(file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, context.getString(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                }
                if (!launched) showToast(R.string.share_apk_failed)
            } finally {
                if (!launched) withContext(NonCancellable) { rootService.deleteRecursively(file.parentFile!!.path) }
            }
        }
    }

    fun loadFurtherOperations() {
        if (_furtherOperations.value == FurtherOperationsUiState.Loading) return
        viewModelScope.launch {
            val app = (uiState.value as? Success.App)?.app ?: return@launch
            val installed = (uiState.value as? Success.App)?.isInstalled == true
            _furtherOperations.value = FurtherOperationsUiState.Loading
            val operations = withContext(Dispatchers.IO) {
                runCatching { resolveFurtherOperations(app, installed) }.getOrDefault(emptyList())
            }
            _furtherOperations.value = FurtherOperationsUiState.Content(operations)
        }
    }

    fun openFurtherOperation(operation: FurtherOperation) {
        viewModelScope.launch {
            val userId = (uiState.value as? Success.App)?.app?.userId ?: return@launch
            val launched = if (operation.intent.data?.scheme == "file") {
                withContext(Dispatchers.Main.immediate) {
                    runCatching { context.startActivity(operation.intent) }.isSuccess
                }
            } else {
                rootService.startActivity(operation.intent, userId)
            }
            if (!launched) showToast(R.string.external_action_failed)
        }
    }

    private suspend fun resolveFurtherOperations(app: PackageEntity, installed: Boolean): List<FurtherOperation> {
        val operations = mutableListOf<FurtherOperation>()
        if (installed) {
            val appInfoIntent = Intent(Intent.ACTION_SHOW_APP_INFO)
            queryIntentActivities(appInfoIntent).forEach { resolveInfo ->
                operations += resolveInfo.toFurtherOperation(
                    Intent(Intent.ACTION_SHOW_APP_INFO)
                        .setComponent(resolveInfo.componentName)
                        .putExtra(Intent.EXTRA_PACKAGE_NAME, app.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }

            rootService.getPackageSourceDir(app.packageName, app.userId).firstOrNull()?.let { source ->
                allowFileUriExposure()
                val sourceUri = android.net.Uri.fromFile(File(source))
                val viewIntent = Intent(Intent.ACTION_VIEW).setDataAndType(sourceUri, APK_MIME_TYPE)
                queryIntentActivities(viewIntent).forEach { resolveInfo ->
                    if (isFileManager(resolveInfo.activityInfo.packageName)) {
                        operations += resolveInfo.toFurtherOperation(
                            Intent(Intent.ACTION_VIEW)
                                .setPackage(resolveInfo.activityInfo.packageName)
                                .setDataAndType(sourceUri, APK_MIME_TYPE)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    }
                }
            }
        }

        val marketIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${app.packageName}"))
        queryIntentActivities(marketIntent).forEach { resolveInfo ->
            operations += resolveInfo.toFurtherOperation(
                Intent(marketIntent)
                    .setComponent(resolveInfo.componentName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        return operations
            .filterNot { it.packageName == context.packageName }
            .distinctBy(FurtherOperation::packageName)
    }

    private fun queryIntentActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun ResolveInfo.toFurtherOperation(intent: Intent) = FurtherOperation(
        packageName = activityInfo.packageName,
        label = loadLabel(context.packageManager).toString(),
        intent = intent,
    )

    private val ResolveInfo.componentName: ComponentName
        get() = ComponentName(activityInfo.packageName, activityInfo.name)

    private fun isFileManager(packageName: String): Boolean {
        val canHandleFiles = queryIntentActivities(
            Intent(Intent.ACTION_VIEW)
                .setPackage(packageName)
                .setDataAndType(android.net.Uri.parse("file:///"), "*/*")
        ).any { it.activityInfo.packageName == packageName }
        val canPickFiles = queryIntentActivities(
            Intent(Intent.ACTION_GET_CONTENT).setPackage(packageName).setType("*/*")
        ).any { it.activityInfo.packageName == packageName }
        val canManageDirectories = queryIntentActivities(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).setPackage(packageName)
        ).any { it.activityInfo.packageName == packageName }
        val permissions = getRequestedPermissions(packageName)
        val hasStoragePermission = permissions.any {
            it == Manifest.permission.MANAGE_EXTERNAL_STORAGE ||
                it == Manifest.permission.READ_EXTERNAL_STORAGE ||
                it == Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return (canHandleFiles || canPickFiles || canManageDirectories) && hasStoragePermission
    }

    private fun getRequestedPermissions(packageName: String): List<String> = runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
        packageInfo.requestedPermissions.orEmpty().toList()
    }.getOrDefault(emptyList())

    private fun allowFileUriExposure() {
        if (!fileUriExposureAttempted.compareAndSet(false, true)) return
        runCatching {
            StrictMode::class.java.getDeclaredMethod("disableDeathOnFileUriExposure").invoke(null)
        }
    }

    private suspend fun loadAppRuntimeInfo(app: PackageEntity) {
        val info = rootService.getPackageInfoAsUser(app.packageName, 0, app.userId)
        val applicationInfo = info?.applicationInfo
        val archiveAbis = rootService.getPackageSourceDir(app.packageName, app.userId)
            .flatMap(::readApkAbis)
            .distinct()
        val nativeAbi = applicationInfo?.nativeLibraryDir?.substringAfterLast('/')?.toAndroidAbi()
        appRuntimeInfo.emit(
            AppRuntimeInfo(
                architecture = archiveAbis.ifEmpty { listOfNotNull(nativeAbi) }.joinToString(),
                targetSdk = applicationInfo?.targetSdkVersion ?: 0,
            )
        )
    }

    private fun readApkAbis(path: String): List<String> = runCatching {
        ZipFile(path).use { archive ->
            archive.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.substringAfter("lib/").substringBefore('/') }
                .distinct()
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun String.toAndroidAbi(): String? = when (this) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        "x86", "x86_64" -> this
        else -> null
    }

    fun protect() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    appsRepo.protectApp(app.indexInfo.cloud, app)
                    showToast(R.string.backup_protected)
                }

                else -> {
                    val state = uiState.value.castTo<Success.File>()
                    val file = state.file
                    filesRepo.protectFile(file.indexInfo.cloud, file)
                    showToast(R.string.backup_protected)
                }
            }
        }
    }

    fun delete() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    showToast(if (appBackupRepository.deleteBackup(app)) R.string.deleted_successfully else R.string.failed)
                }

                is Success.File -> {
                    val state = uiState.value.castTo<Success.File>()
                    val file = state.file
                    when (file.indexInfo.opType) {
                        OpType.BACKUP -> {
                            filesRepo.delete(file.id)
                            showToast(R.string.deleted_successfully)
                        }

                        OpType.RESTORE -> {
                            showToast(if (filesRepo.deleteFile(file)) R.string.deleted_successfully else R.string.failed)
                        }
                    }
                }

                else -> {}
            }
        }
    }

    private suspend fun showToast(@StringRes message: Int) = withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun showToast(message: String) = withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val BINARY_MIME_TYPE = "application/octet-stream"
        val fileUriExposureAttempted = AtomicBoolean(false)
    }
}

data class FurtherOperation(
    val packageName: String,
    val label: String,
    val intent: Intent,
)

sealed interface FurtherOperationsUiState {
    data object Idle : FurtherOperationsUiState
    data object Loading : FurtherOperationsUiState
    data class Content(val operations: List<FurtherOperation>) : FurtherOperationsUiState
}

sealed interface DetailsUiState {
    data object Loading : DetailsUiState
    sealed class Success(
        open val isRefreshing: Boolean,
        open val labels: List<ColoredLabel>,
    ) : DetailsUiState {
        data class App(
            override val isRefreshing: Boolean,
            override val labels: List<ColoredLabel>,
            val app: PackageEntity,
            val refs: List<LabelAppCrossRefEntity>,
            val architecture: String,
            val targetSdk: Int,
            val isInstalled: Boolean,
            val note: String,
        ) : Success(isRefreshing, labels)

        data class File(
            override val isRefreshing: Boolean,
            override val labels: List<ColoredLabel>,
            val file: MediaEntity,
            val refs: List<LabelFileCrossRefEntity>,
        ) : Success(isRefreshing, labels)
    }

    data object Error : DetailsUiState
}

private data class AppRuntimeInfo(
    val architecture: String = "",
    val targetSdk: Int = 0,
)

private data class AppDetailsSource(val app: PackageEntity, val isInstalled: Boolean, val note: String = "")

private fun BackupAppEntity.toPackageEntity(isXposedModule: Boolean) = PackageEntity(
    id = 0,
    indexInfo = PackageIndexInfo(OpType.RESTORE, packageName, userId, CompressionType.ZSTD, 0, "", ""),
    packageInfo = PackageInfo(
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        flags = if (isSystem) android.content.pm.ApplicationInfo.FLAG_SYSTEM else 0,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        isXposedModule = isXposedModule,
    ),
    extraInfo = PackageExtraInfo(0, false, emptyList(), "", 0, false, false, true, false),
    dataStates = PackageDataStates(
        apkState = DataState.NotSelected,
        userState = DataState.NotSelected,
        userDeState = DataState.NotSelected,
        dataState = DataState.NotSelected,
        obbState = DataState.NotSelected,
        mediaState = DataState.NotSelected,
        permissionState = DataState.NotSelected,
        ssaidState = DataState.NotSelected,
    ),
    storageStats = PackageStorageStats(),
    dataStats = PackageDataStats(),
    displayStats = PackageDataStats(),
)
