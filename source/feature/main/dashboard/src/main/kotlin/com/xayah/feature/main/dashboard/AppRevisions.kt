package com.xayah.feature.main.dashboard

import android.text.format.DateUtils
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.model.DataState
import com.xayah.core.util.DateUtil
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStates.Companion.setSelected
import com.xayah.core.model.database.PackageDataStates.Companion.getSelected
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.model.isProtectedByNote
import com.xayah.core.ui.component.DataChips
import com.xayah.core.ui.component.PackageDataStatesSaver
import com.xayah.core.ui.component.TooltipIconButton
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.util.LocalNavController
import com.xayah.feature.main.details.AppDetails
import com.xayah.feature.main.details.DetailsUiState
import com.xayah.feature.main.details.DetailsViewModel
import com.xayah.feature.main.details.NoteEditorDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRevisionsRoute(
    viewModel: AppRevisionsViewModel = hiltViewModel(),
    detailsViewModel: DetailsViewModel = hiltViewModel(),
) {
    val app by viewModel.app.collectAsStateWithLifecycle()
    val revisions by viewModel.revisions.collectAsStateWithLifecycle()
    val installedApp by viewModel.installedApp.collectAsStateWithLifecycle()
    val verificationResults by viewModel.verificationResults.collectAsStateWithLifecycle()
    val verifyingRevisionIds by viewModel.verifyingRevisionIds.collectAsStateWithLifecycle()
    val damagedRestoreRequest by viewModel.damagedRestoreRequest.collectAsStateWithLifecycle()
    val detailsState by detailsViewModel.uiState.collectAsStateWithLifecycle()
    val furtherOperations by detailsViewModel.furtherOperations.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    var selectedRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreCandidate by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteCandidate by rememberSaveable { mutableStateOf<String?>(null) }
    var noteCandidate by rememberSaveable { mutableStateOf<String?>(null) }
    var showBackupScope by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var blacklistConfirmation by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var uninstallKeepingDataConfirmation by rememberSaveable { mutableStateOf(false) }
    val appDetails = detailsState as? DetailsUiState.Success.App

    LaunchedEffect(Unit) {
        detailsViewModel.refresh(initial = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(app?.label ?: stringResource(R.string.backup_revisions)) },
                navigationIcon = {
                    TooltipIconButton(tooltip = stringResource(R.string.back), onClick = navController::navigateUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (appDetails != null && installedApp != null) {
                        TooltipIconButton(tooltip = stringResource(R.string.share), onClick = detailsViewModel::shareApk) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.share))
                        }
                        Box {
                            TooltipIconButton(tooltip = stringResource(R.string.more_options), onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_cache)) },
                                    leadingIcon = { Icon(Icons.Rounded.CleaningServices, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        detailsViewModel.clearAppCache()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_settings)) },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        detailsViewModel.openAppSettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.uninstall_keep_data)) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        uninstallKeepingDataConfirmation = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (appDetails.app.extraInfo.blocked) R.string.remove_from_blacklist
                                                else R.string.add_to_blacklist
                                            )
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        blacklistConfirmation = appDetails.app.extraInfo.blocked
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(DashboardDimens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(DashboardDimens.ItemSpacing),
        ) {
            item {
                Text(
                    text = stringResource(R.string.backup_revisions),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (installedApp != null) {
                item {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showBackupScope = true },
                    ) {
                        Text(stringResource(R.string.back_up))
                    }
                }
            }
            if (revisions.isEmpty()) {
                item {
                    Text(
                        modifier = Modifier.padding(vertical = DashboardDimens.EmptyPadding),
                        text = stringResource(R.string.no_backup_revisions),
                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    )
                }
            } else {
                items(revisions, key = BackupRevisionEntity::id) { revision ->
                    RevisionItem(
                        revision = revision,
                        verificationStatus = verificationResults[revision.id]?.status,
                        onClick = { selectedRevision = revision.id },
                    )
                }
            }

            if (detailsState is DetailsUiState.Success.App) {
                val currentDetails = detailsState as DetailsUiState.Success.App
                item {
                    AppDetails(
                        uiState = currentDetails,
                        onSetDataStates = detailsViewModel::setDataStates,
                        onAddLabel = detailsViewModel::addLabel,
                        onDeleteLabel = detailsViewModel::deleteLabel,
                        onUpdateLabel = detailsViewModel::updateLabel,
                        onSelectLabel = detailsViewModel::selectAppLabel,
                        onUpdateAppNote = detailsViewModel::updateAppNote,
                        onUninstall = detailsViewModel::uninstallApp,
                        onClearData = detailsViewModel::clearAppData,
                        onResolveDataPath = detailsViewModel::resolveDataPath,
                        onCopyPath = detailsViewModel::copyPath,
                        onOpenPath = detailsViewModel::openPath,
                        onEditPermissions = {
                            navController.navigate(
                                MainRoutes.PermissionEditor.getRoute(
                                    currentDetails.app.packageName,
                                    currentDetails.app.userId,
                                )
                            )
                        },
                        onSaveAppIcon = detailsViewModel::saveAppIcon,
                        onShareApk = detailsViewModel::shareApk,
                        onCopyAppName = detailsViewModel::copyAppName,
                        onCopyPackageName = detailsViewModel::copyPackageName,
                        furtherOperations = furtherOperations,
                        onLoadFurtherOperations = detailsViewModel::loadFurtherOperations,
                        onOpenFurtherOperation = detailsViewModel::openFurtherOperation,
                        onFreeze = detailsViewModel::freezeApp,
                        onLaunch = detailsViewModel::launchApp,
                        onProtect = detailsViewModel::protect,
                        onDelete = detailsViewModel::delete,
                        iconRefreshKey = verificationResults,
                    )
                }
            }
        }
    }

    blacklistConfirmation?.let { blocked ->
        AlertDialog(
            onDismissRequest = { blacklistConfirmation = null },
            title = { Text(stringResource(R.string.blacklist)) },
            text = {
                Text(stringResource(if (blocked) R.string.confirm_remove_from_blacklist else R.string.confirm_add_to_blacklist))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        blacklistConfirmation = null
                        detailsViewModel.block(blocked)
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { blacklistConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (uninstallKeepingDataConfirmation) {
        AlertDialog(
            onDismissRequest = { uninstallKeepingDataConfirmation = false },
            title = { Text(stringResource(R.string.uninstall_keep_data)) },
            text = { Text(stringResource(R.string.confirm_uninstall_keep_data)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        uninstallKeepingDataConfirmation = false
                        detailsViewModel.uninstallAppKeepingData()
                    },
                ) {
                    Text(stringResource(R.string.uninstall))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { uninstallKeepingDataConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    revisions.firstOrNull { it.id == selectedRevision }?.let { revision ->
        RevisionDetailsDialog(
            revision = revision,
            verificationResult = verificationResults[revision.id],
            isVerifying = revision.id in verifyingRevisionIds,
            onDismiss = { selectedRevision = null },
            onRestore = {
                selectedRevision = null
                restoreCandidate = revision.id
            },
            onDelete = {
                selectedRevision = null
                deleteCandidate = revision.id
            },
            onVerify = { viewModel.verifyRevision(revision) },
            onEditNote = {
                selectedRevision = null
                noteCandidate = revision.id
            },
        )
    }

    revisions.firstOrNull { it.id == noteCandidate }?.let { revision ->
        NoteEditorDialog(
            note = revision.note,
            onDismiss = { noteCandidate = null },
            onConfirm = {
                viewModel.updateRevisionNote(revision, it)
                noteCandidate = null
            },
        )
    }

    revisions.firstOrNull { it.id == restoreCandidate }?.let { revision ->
        RestoreScopeDialog(
            revision = revision,
            onDismiss = { restoreCandidate = null },
            onConfirm = { dataStates ->
                restoreCandidate = null
                viewModel.startRestore(revision, dataStates, navController)
            },
        )
    }

    revisions.firstOrNull { it.id == deleteCandidate }?.let { revision ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_revision)) },
            text = { Text(stringResource(R.string.confirm_delete_revision)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        deleteCandidate = null
                        viewModel.deleteRevision(revision)
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    installedApp?.takeIf { showBackupScope }?.let { candidate ->
        BackupScopeDialog(
            app = candidate,
            onDismiss = { showBackupScope = false },
            onConfirm = { dataStates ->
                showBackupScope = false
                viewModel.startBackup(dataStates, navController)
            },
        )
    }

    damagedRestoreRequest?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDamagedRestoreWarning,
            title = { Text(stringResource(R.string.backup_damaged)) },
            text = { Text(stringResource(R.string.backup_damaged_restore_warning)) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.dismissDamagedRestoreWarning()
                        viewModel.startRestore(request.revision, request.dataStates, navController, allowDamaged = true)
                    },
                ) {
                    Text(stringResource(R.string.restore_anyway))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::dismissDamagedRestoreWarning) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BackupScopeDialog(
    app: PackageEntity,
    onDismiss: () -> Unit,
    onConfirm: (PackageDataStates) -> Unit,
) {
    var selections by rememberSaveable(app.id, stateSaver = PackageDataStatesSaver) { mutableStateOf(app.dataStates.copy()) }
    val hasSelection = with(selections) {
        apkState == DataState.Selected || userState == DataState.Selected ||
            userDeState == DataState.Selected || dataState == DataState.Selected ||
            obbState == DataState.Selected || mediaState == DataState.Selected
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_scope)) },
        text = {
            DataChips(
                selections = selections,
                displayStats = app.displayStats,
                maxItemsInEachRow = 1,
            ) { type, selected ->
                selections = type.setSelected(selections, selected.not())
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = hasSelection,
                onClick = { onConfirm(selections) },
            ) {
                Text(stringResource(R.string.back_up))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RestoreScopeDialog(
    revision: BackupRevisionEntity,
    onDismiss: () -> Unit,
    onConfirm: (PackageDataStates) -> Unit,
) {
    val available = remember(revision.contentMask) { revision.contentMask.toDataStates() }
    var selections by rememberSaveable(revision.id, stateSaver = PackageDataStatesSaver) { mutableStateOf(available) }
    val hasSelection = with(selections) {
        apkState == DataState.Selected || userState == DataState.Selected ||
            userDeState == DataState.Selected || dataState == DataState.Selected ||
            obbState == DataState.Selected || mediaState == DataState.Selected
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_scope)) },
        text = {
            DataChips(
                selections = selections,
                maxItemsInEachRow = 1,
                isEnabled = { type -> type.getSelected(available) },
            ) { type, selected ->
                selections = type.setSelected(selections, !selected)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = hasSelection, onClick = { onConfirm(selections) }) {
                Text(stringResource(R.string.restore))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun Int.toDataStates() = PackageDataStates(
    apkState = if (this and 1 != 0) DataState.Selected else DataState.NotSelected,
    userState = if (this and 2 != 0) DataState.Selected else DataState.NotSelected,
    userDeState = if (this and 4 != 0) DataState.Selected else DataState.NotSelected,
    dataState = if (this and 8 != 0) DataState.Selected else DataState.NotSelected,
    obbState = if (this and 16 != 0) DataState.Selected else DataState.NotSelected,
    mediaState = if (this and 32 != 0) DataState.Selected else DataState.NotSelected,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RevisionItem(
    revision: BackupRevisionEntity,
    verificationStatus: BackupVerificationStatus?,
    onClick: () -> Unit,
) {
    val contents = buildList {
        if (revision.contentMask and 1 != 0) add(stringResource(R.string.apk))
        if (revision.contentMask and 62 != 0) add(stringResource(R.string.app_data))
    }.joinToString()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(DashboardDimens.ItemPadding),
            verticalArrangement = Arrangement.spacedBy(DashboardDimens.ItemSpacing),
        ) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(DashboardDimens.ItemSpacing),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = revision.appVersionName.ifEmpty { revision.appVersionCode.toString() },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (revision.isProtectedByNote()) ProtectedMarker()
            }
            Text(
                text = DateUtils.getRelativeTimeSpanString(revision.createdAt).toString(),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = contents,
                style = MaterialTheme.typography.bodySmall,
                color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
            )
            Text(
                text = revision.sizeBytes.toDouble().formatSize(),
                style = MaterialTheme.typography.bodySmall,
                color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
            )
            if (revision.note.isNotEmpty()) {
                Text(
                    text = revision.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    maxLines = 2,
                )
            }
            verificationStatus?.let { status ->
                Text(
                    text = stringResource(status.stringRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == BackupVerificationStatus.VALID) {
                        ThemedColorSchemeKeyTokens.GreenPrimary.value
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun RevisionDetailsDialog(
    revision: BackupRevisionEntity,
    verificationResult: AppBackupRepository.VerificationResult?,
    isVerifying: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onVerify: () -> Unit,
    onEditNote: () -> Unit,
) {
    val contents = buildList {
        if (revision.contentMask and 1 != 0) add(stringResource(R.string.apk))
        if (revision.contentMask and 2 != 0) add("USER")
        if (revision.contentMask and 4 != 0) add("USER_DE")
        if (revision.contentMask and 8 != 0) add("DATA")
        if (revision.contentMask and 16 != 0) add("OBB")
        if (revision.contentMask and 32 != 0) add("MEDIA")
    }.joinToString()
    val size = revision.sizeBytes.toDouble().formatSize()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.revision_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DashboardDimens.ItemSpacing)) {
                Text(revision.appVersionName.ifEmpty { revision.appVersionCode.toString() })
                Text(stringResource(R.string.created_at, DateUtil.formatTimestamp(revision.createdAt, DateUtil.PATTERN_YMD_HMS)))
                Text(contents)
                Text(size)
                if (revision.isProtectedByNote()) ProtectedMarker()
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = revision.note.ifEmpty { stringResource(R.string.no_note) },
                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    )
                    TooltipIconButton(tooltip = stringResource(R.string.edit_note), onClick = onEditNote) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                    }
                }
                verificationResult?.let { result ->
                    Text(
                        text = stringResource(result.status.stringRes()),
                        color = if (result.status == BackupVerificationStatus.VALID) {
                            ThemedColorSchemeKeyTokens.GreenPrimary.value
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                verificationResult?.issues?.forEach { issue ->
                    Text(
                        text = issue.description(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isVerifying) {
                    Text(
                        text = stringResource(R.string.verifying),
                        color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(
                    enabled = revision.engine == BackupEngine.LEGACY && !isVerifying,
                    onClick = onVerify,
                ) {
                    Text(stringResource(if (isVerifying) R.string.verifying else R.string.verify))
                }
                androidx.compose.material3.TextButton(
                    enabled = revision.engine == BackupEngine.LEGACY,
                    onClick = onRestore,
                ) {
                    Text(stringResource(R.string.restore))
                }
                androidx.compose.material3.TextButton(
                    enabled = revision.engine == BackupEngine.LEGACY,
                    onClick = onDelete,
                ) {
                    Text(stringResource(R.string.delete_revision), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProtectedMarker() {
    val color = ThemedColorSchemeKeyTokens.YellowPrimary.value
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(DashboardDimens.ItemSpacing),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.Level16),
            tint = color,
        )
        Text(
            text = stringResource(R.string._protected),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun BackupVerificationStatus.stringRes() = when (this) {
    BackupVerificationStatus.NOT_VERIFIED -> R.string.not_verified
    BackupVerificationStatus.VALID -> R.string.backup_valid
    BackupVerificationStatus.DAMAGED -> R.string.backup_damaged
}

@Composable
private fun AppBackupRepository.VerificationIssue.description(): String {
    val message = stringResource(
        when (type) {
            AppBackupRepository.VerificationIssueType.MANIFEST_MISSING -> R.string.verification_manifest_missing
            AppBackupRepository.VerificationIssueType.MANIFEST_INVALID -> R.string.verification_manifest_invalid
            AppBackupRepository.VerificationIssueType.METADATA_MISMATCH -> R.string.verification_metadata_mismatch
            AppBackupRepository.VerificationIssueType.FILE_MISSING -> R.string.verification_file_missing
            AppBackupRepository.VerificationIssueType.FILE_SIZE_MISMATCH -> R.string.verification_file_size_mismatch
            AppBackupRepository.VerificationIssueType.FILE_CHECKSUM_MISMATCH -> R.string.verification_file_checksum_mismatch
            AppBackupRepository.VerificationIssueType.NOT_LOCAL -> R.string.verification_not_local
        }
    )
    return fileName?.let { "$it: $message" } ?: message
}
