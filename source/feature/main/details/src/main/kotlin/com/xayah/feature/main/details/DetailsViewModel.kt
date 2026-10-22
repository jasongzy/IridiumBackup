package com.xayah.feature.main.details

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.AppIconRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.LabelsRepo
import com.xayah.core.data.repository.ListDataRepo
import com.xayah.core.data.util.srcDir
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.common.util.BuildConfigUtil
import com.xayah.core.model.OpType
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
import com.xayah.core.util.decodeURL
import com.xayah.core.util.launchOnDefault
import com.xayah.core.util.PathUtil
import com.xayah.core.util.command.BaseUtil
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import javax.inject.Inject

private const val ICON_EXPORT_DIR = "IridiumBackup"

@HiltViewModel
class DetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val rootService: RemoteRootService,
    private val appsRepo: AppsRepo,
    private val appBackupRepository: AppBackupRepository,
    private val appIconRepository: AppIconRepository,
    private val filesRepo: FilesRepo,
    private val labelsRepo: LabelsRepo,
    private val listDataRepo: ListDataRepo,
) : ViewModel() {
    private val id: Long = savedStateHandle.get<String>(MainRoutes.ARG_ID)?.toLongOrNull() ?: 0L
    private val packageName = savedStateHandle.get<String>(MainRoutes.ARG_PACKAGE_NAME)
    private val userId = savedStateHandle.get<String>(MainRoutes.ARG_USER_ID)?.toIntOrNull() ?: 0
    private val target: Target = savedStateHandle.get<String>(MainRoutes.ARG_TARGET)
        ?.let { Target.valueOf(it.decodeURL().trim()) }
        ?: Target.Apps
    private val isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val appRuntimeInfo = MutableStateFlow(AppRuntimeInfo())
    private val shareMutex = Mutex()
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
                val indexedAppFlow = combine(
                    appBackupRepository.observeApp(packageName, userId),
                    appsRepo.getRestoreApps(),
                ) { indexed, restoreApps ->
                    indexed?.let { app ->
                        app to restoreApps.any {
                            it.packageName == packageName &&
                                it.userId == userId &&
                                it.packageInfo.isXposedModule
                        }
                    }
                }
                combine(installedAppFlow, indexedAppFlow) { installed, indexedState ->
                    installed?.let { AppDetailsSource(it, true, indexedState?.first?.note.orEmpty()) }
                        ?: indexedState?.let { (indexed, isXposedModule) ->
                            AppDetailsSource(indexed.toPackageEntity(isXposedModule), false, indexed.note)
                        }
                }
            } else {
                installedAppFlow.combine(kotlinx.coroutines.flow.flowOf(null as BackupAppEntity?)) { installed, _ ->
                    installed?.let { AppDetailsSource(it, true) }
                }
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
                            labels.find { it.label == ref.label } != null && ref.packageName == app.packageName && ref.userId == app.userId && ref.preserveId == app.preserveId
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
                    preserveId = app.preserveId,
                )
            )
        }
    }

    fun deleteLabel(label: String) {
        viewModelScope.launchOnDefault {
            labelsRepo.deleteLabel(label)
            listDataRepo.removeLabelFilter(label)
            appBackupRepository.removeUnusedApps()
        }
    }

    fun updateAppNote(note: String) {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            appBackupRepository.updateAppNote(app.packageName, app.userId, note)
            showToast(R.string.note_saved)
        }
    }

    fun updateLabel(oldLabel: String, newLabel: String, colorArgb: Long) {
        viewModelScope.launchOnDefault {
            val normalized = newLabel.trim()
            if (oldLabel != normalized) {
                labelsRepo.renameLabel(oldLabel, normalized)
                listDataRepo.renameLabelFilter(oldLabel, normalized)
            }
            labelsRepo.setLabelColor(normalized, colorArgb)
        }
    }

    fun selectAppLabel(selected: Boolean, ref: LabelAppCrossRefEntity?) {
        viewModelScope.launchOnDefault {
            if (ref != null) {
                if (selected) {
                    labelsRepo.deleteLabelAppCrossRef(ref)
                    appBackupRepository.removeUnusedApps(ref.userId)
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

    fun block(blocked: Boolean) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    appsRepo.blockByIds(listOf(state.app.id))
                    showToast(if (blocked) R.string.removed_from_blacklist else R.string.added_to_blacklist)
                }

                is Success.File -> {
                    val state = uiState.value.castTo<Success.File>()
                    filesRepo.blockByIds(listOf(state.file.id))
                    showToast(if (blocked) R.string.removed_from_blacklist else R.string.added_to_blacklist)
                }

                else -> {}
            }
        }
    }

    fun freezeApp(frozen: Boolean) {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    if (frozen) {
                        rootService.setApplicationEnabledSetting(app.packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, app.userId, null)
                    } else {
                        rootService.setApplicationEnabledSetting(app.packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, app.userId, null)
                    }
                    val enabled = rootService.getApplicationEnabledSetting(app.packageName, app.userId) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    appsRepo.setEnabled(app.id, enabled)
                    if (enabled == frozen) showToast(if (frozen) R.string.app_unfrozen else R.string.app_frozen)
                }

                else -> {}
            }
        }
    }

    fun launchApp() {
        viewModelScope.launchOnDefault {
            when (uiState.value) {
                is Success.App -> {
                    val state = uiState.value.castTo<Success.App>()
                    val app = state.app
                    appsRepo.launchApp(app.packageName, app.userId)
                }

                else -> {}
            }
        }
    }

    fun uninstallApp() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (rootService.uninstallPackageAsUser(app.packageName, app.userId)) {
                appsRepo.removeUninstalledApp(app.packageName, app.userId)
                showToast(R.string.app_uninstalled)
            }
        }
    }

    fun uninstallAppKeepingData() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (rootService.uninstallPackageKeepingDataAsUser(app.packageName, app.userId)) {
                appsRepo.removeUninstalledApp(app.packageName, app.userId)
                showToast(R.string.app_uninstalled_data_kept)
            }
        }
    }

    fun clearAppData() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (rootService.clearPackageDataAsUser(app.packageName, app.userId)) {
                appsRepo.calculateLocalAppSize(app)
                showToast(R.string.app_data_cleared)
            }
        }
    }

    fun clearAppCache() {
        viewModelScope.launchOnDefault {
            val app = (uiState.value as? Success.App)?.app ?: return@launchOnDefault
            if (rootService.clearPackageCacheAsUser(app.packageName, app.userId)) {
                appsRepo.calculateLocalAppSize(app)
                showToast(R.string.app_cache_cleared)
            }
        }
    }

    fun openAppSettings() {
        val app = (uiState.value as? Success.App)?.app ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            viewModelScope.launch { showToast(R.string.external_action_failed) }
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
            val icon = runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                ?: BaseUtil.readIcon(context, appIconRepository.getLocalIconPath(app.packageName))
            val saved = icon != null && saveIcon(icon.toBitmap(512, 512, Bitmap.Config.ARGB_8888), app.packageName)
            showToast(if (saved) R.string.app_icon_saved else R.string.app_icon_save_failed)
        }
    }

    fun shareApk() {
        viewModelScope.launch {
            val app = (uiState.value as? Success.App)?.app ?: return@launch
            val file = withContext(Dispatchers.IO) {
                shareMutex.withLock {
                    prepareAppShareFile(context.externalCacheDir ?: context.cacheDir, app, rootService)
                }
            }
            if (file == null) {
                showToast(R.string.share_apk_failed)
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                context,
                fileProviderAuthority,
                file,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.extension.equals("apk", ignoreCase = true)) APK_MIME_TYPE else BINARY_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = withContext(Dispatchers.Main.immediate) {
                runCatching { context.startActivity(chooser) }.isSuccess
            }
            if (!launched) showToast(R.string.share_apk_failed)
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

    private fun saveIcon(bitmap: Bitmap, packageName: String): Boolean {
        val fileName = "${packageName}_${System.currentTimeMillis()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ICON_EXPORT_DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            val saved = runCatching {
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            }.getOrDefault(false)
            if (saved) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } else {
                resolver.delete(uri, null, null)
            }
            saved
        } else {
            saveLegacyIcon(bitmap, fileName)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacyIcon(bitmap: Bitmap, fileName: String): Boolean = runCatching {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ICON_EXPORT_DIR,
        )
        if (directory.exists().not() && directory.mkdirs().not()) return@runCatching false
        val file = File(directory, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null)
        true
    }.getOrDefault(false)

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
                    appsRepo.deleteApp(app.indexInfo.cloud, app)
                    showToast(R.string.deleted_successfully)
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
                            filesRepo.deleteFile(file.indexInfo.cloud, file)
                            showToast(R.string.deleted_successfully)
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

    private val fileProviderAuthority: String
        get() = "com.xayah.core.provider.FileSharingProvider.${BuildConfigUtil.FLAVOR_feature.lowercase()}"
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
