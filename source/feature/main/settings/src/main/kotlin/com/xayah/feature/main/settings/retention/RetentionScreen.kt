package com.xayah.feature.main.settings.retention

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.isProtectedByNote
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.component.LinearProgressIndicator
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.TooltipIconButton
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageBackupRetention(viewModel: RetentionViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    val focusManager = LocalFocusManager.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val isBusy = state.isScanning || state.isDeleting
    val validCount = state.retainCount.toIntOrNull()?.let { it >= 1 } == true
    val removableCount = state.candidates.count { it.removable }
    val groups = state.candidates.groupBy { AppKey(it.revision.packageName, it.revision.userId) }
    var collapsedApps by rememberSaveable(state.candidates) { mutableStateOf(emptyList<String>()) }
    var detailRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(R.string.clean_old_backups),
        actions = {},
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(SizeTokens.Level16),
                verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
            ) {
                OutlinedTextField(
                    value = state.retainCount,
                    onValueChange = viewModel::setRetainCount,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy,
                    isError = state.retainCount.isNotEmpty() && !validCount,
                    label = { Text(stringResource(R.string.backups_to_keep)) },
                    supportingText = { Text(stringResource(R.string.protected_backups_retained)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { scan(viewModel, focusManager, validCount) }),
                    singleLine = true,
                )
                if (state.isScanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.isDeleting) {
                    LinearProgressIndicator(
                        progress = if (state.deleteTotal == 0) 0f else state.completed.toFloat() / state.deleteTotal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.old_backup_cleanup_progress, state.completed, state.deleteTotal))
                } else if (state.hasScanned) {
                    Text(
                        text = if (removableCount == 0) stringResource(R.string.no_old_backups)
                        else stringResource(R.string.old_backups_found, removableCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (removableCount > 0) {
                        SelectionActions(
                            selectedCount = state.selectedIds.size,
                            enabled = !isBusy,
                            onSelectAll = { viewModel.selectAll(true) },
                            onUnselectAll = { viewModel.selectAll(false) },
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                groups.forEach { (key, candidates) ->
                    val groupId = "${key.packageName}:${key.userId}"
                    item(key = "app:${key.packageName}:${key.userId}") {
                        AppHeader(
                            candidate = candidates.first(),
                            candidates = candidates,
                            selectedIds = state.selectedIds,
                            expanded = groupId !in collapsedApps,
                            enabled = !isBusy,
                            onOpen = { navController.navigateSingle(MainRoutes.AppRevisions.getRoute(key.packageName, key.userId)) },
                            onToggle = { viewModel.toggleGroup(key.packageName, key.userId) },
                            onToggleExpanded = {
                                collapsedApps = if (groupId in collapsedApps) collapsedApps - groupId else collapsedApps + groupId
                            },
                        )
                    }
                    if (groupId !in collapsedApps) {
                        items(candidates, key = { it.revision.id }) { candidate ->
                            BackupCandidateItem(
                                candidate = candidate,
                                latest = candidate.revision.id == candidates.first().revision.id,
                                selected = candidate.revision.id in state.selectedIds,
                                enabled = !isBusy,
                                onToggle = { viewModel.toggle(candidate.revision.id) },
                                onDetails = { detailRevision = candidate.revision.id },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SizeTokens.Level16),
                enabled = !isBusy && (if (state.hasScanned) state.selectedIds.isNotEmpty() else validCount),
                onClick = {
                    if (state.hasScanned) showDeleteConfirmation = true
                    else scan(viewModel, focusManager, validCount)
                },
            ) {
                Icon(
                    imageVector = if (state.hasScanned) Icons.Outlined.DeleteSweep else Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(end = SizeTokens.Level8),
                )
                Text(
                    if (state.hasScanned) stringResource(R.string.delete_old_backups, state.selectedIds.size)
                    else stringResource(R.string.scan_old_backups),
                )
            }
        }
    }

    state.candidates.firstOrNull { it.revision.id == detailRevision }?.revision?.let { revision ->
        BackupDetailsDialog(revision = revision, onDismiss = { detailRevision = null })
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.clean_old_backups)) },
            text = { Text(stringResource(R.string.confirm_delete_old_backups, state.selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun scan(viewModel: RetentionViewModel, focusManager: FocusManager, validCount: Boolean) {
    if (!validCount) return
    focusManager.clearFocus()
    viewModel.scan()
}

@Composable
private fun SelectionActions(selectedCount: Int, enabled: Boolean, onSelectAll: () -> Unit, onUnselectAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
    ) {
        Text(stringResource(R.string.selected_count, selectedCount), modifier = Modifier.weight(1f))
        OutlinedButton(enabled = enabled, onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
        OutlinedButton(enabled = enabled, onClick = onUnselectAll) { Text(stringResource(R.string.unselect_all)) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AppHeader(
    candidate: AppBackupRepository.RetentionCandidate,
    candidates: List<AppBackupRepository.RetentionCandidate>,
    selectedIds: Set<String>,
    expanded: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val removableIds = candidates.filter { it.removable }.map { it.revision.id }
    val selectedCount = removableIds.count(selectedIds::contains)
    val state = when {
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == removableIds.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onOpen),
        leadingContent = {
            PackageIconImage(
                packageName = candidate.revision.packageName,
                size = SizeTokens.Level48,
                refreshKey = candidate.revision.id,
            )
        },
        headlineContent = { Text(candidate.appLabel) },
        supportingContent = { Text(candidate.revision.packageName) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(state = state, enabled = enabled && removableIds.isNotEmpty(), onClick = onToggle)
                TooltipIconButton(
                    tooltip = stringResource(if (expanded) R.string.collapse_backups else R.string.expand_backups),
                    onClick = onToggleExpanded,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.collapse_backups else R.string.expand_backups),
                    )
                }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BackupCandidateItem(
    candidate: AppBackupRepository.RetentionCandidate,
    latest: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onDetails: () -> Unit,
) {
    val revision = candidate.revision
    ListItem(
        modifier = Modifier
            .alpha(if (candidate.removable) 1f else 0.55f)
            .padding(start = SizeTokens.Level32)
            .combinedClickable(enabled = enabled, onClick = { if (candidate.removable) onToggle() }, onLongClick = onDetails),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                Text(revision.appVersionName.ifEmpty { revision.appVersionCode.toString() }, modifier = Modifier.weight(1f))
                if (revision.isProtectedByNote()) ProtectedMarker()
                if (latest) Text(stringResource(R.string.latest_backup), style = MaterialTheme.typography.labelSmall)
            }
        },
        supportingContent = {
            Column {
                Text(DateUtil.formatTimestamp(revision.createdAt, DateUtil.PATTERN_YMD_HMS))
                Text(revision.sizeBytes.toDouble().formatSize())
                if (revision.note.isNotEmpty()) Text(revision.note, maxLines = 2)
            }
        },
        trailingContent = {
            Checkbox(checked = selected, enabled = enabled && candidate.removable, onCheckedChange = { onToggle() })
        },
    )
}

@Composable
private fun BackupDetailsDialog(revision: BackupRevisionEntity, onDismiss: () -> Unit) {
    val contents = buildList {
        if (revision.contentMask and 1 != 0) add("APK")
        if (revision.contentMask and 2 != 0) add("USER")
        if (revision.contentMask and 4 != 0) add("USER_DE")
        if (revision.contentMask and 8 != 0) add("DATA")
        if (revision.contentMask and 16 != 0) add("OBB")
        if (revision.contentMask and 32 != 0) add("MEDIA")
    }.joinToString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.retention_backup_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                Text(revision.appVersionName.ifEmpty { revision.appVersionCode.toString() })
                Text(DateUtil.formatTimestamp(revision.createdAt, DateUtil.PATTERN_YMD_HMS))
                Text(contents)
                Text(revision.sizeBytes.toDouble().formatSize())
                if (revision.isProtectedByNote()) ProtectedMarker()
                Text(
                    text = revision.note.ifEmpty { stringResource(R.string.no_note) },
                    color = ThemedColorSchemeKeyTokens.OnSurfaceVariant.value,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun ProtectedMarker() {
    val color = ThemedColorSchemeKeyTokens.YellowPrimary.value
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.Level16),
            tint = color,
        )
        Text(stringResource(R.string._protected), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
