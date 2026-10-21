package com.xayah.feature.main.settings.titanium

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.xayah.core.data.repository.TitaniumImportRepository
import com.xayah.core.ui.component.LinearProgressIndicator
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.DateUtil
import com.xayah.core.util.getActivity
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageTitaniumImport() {
    val viewModel = hiltViewModel<TitaniumImportViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedPath = if (state.mode == ImportMode.BACKUPS) state.backupPath else state.labelPath
    val selectedCount = if (state.mode == ImportMode.BACKUPS) state.selectedBackupIds.size else state.selectedLabels.size
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    var leaveAfterCancel by rememberSaveable { mutableStateOf(false) }
    var expandedPackages by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val requestBack: () -> Unit = {
        if (state.isBusy && !state.cancelling) {
            leaveAfterCancel = true
            showCancelConfirmation = true
        } else if (!state.cancelling) {
            viewModel.discardPreview()
            navController.popBackStack()
        }
    }
    BackHandler { requestBack() }
    LaunchedEffect(state.isBusy, state.cancelling, showCancelConfirmation) {
        if (leaveAfterCancel && !showCancelConfirmation && !state.isBusy && !state.cancelling) {
            leaveAfterCancel = false
            viewModel.discardPreview()
            navController.popBackStack()
        }
    }

    SettingsScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        title = stringResource(R.string.import_from_titanium),
        actions = {},
        onBackClick = requestBack,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level8),
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
            ) {
                ModeButton(
                    selected = state.mode == ImportMode.BACKUPS,
                    enabled = !state.isBusy && !state.cancelling,
                    text = stringResource(R.string.titanium_backups),
                    modifier = Modifier.weight(1f),
                ) { viewModel.emitIntentOnMain(TitaniumImportIntent.SetMode(ImportMode.BACKUPS)) }
                ModeButton(
                    selected = state.mode == ImportMode.LABELS,
                    enabled = !state.isBusy && !state.cancelling,
                    text = stringResource(R.string.titanium_labels),
                    modifier = Modifier.weight(1f),
                ) { viewModel.emitIntentOnMain(TitaniumImportIntent.SetMode(ImportMode.LABELS)) }
            }
            Text(
                text = selectedPath.ifEmpty { stringResource(R.string.source_not_found) },
                modifier = Modifier.padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level8),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SizeTokens.Level16),
                enabled = !state.isBusy && !state.cancelling,
                onClick = {
                    viewModel.emitIntentOnMain(
                        if (state.mode == ImportMode.BACKUPS) TitaniumImportIntent.SelectBackupPath(context.getActivity())
                        else TitaniumImportIntent.SelectLabelPath(context.getActivity())
                    )
                },
            ) { Text(stringResource(R.string.select_source)) }

            if (state.isBusy) {
                Column(modifier = Modifier.padding(SizeTokens.Level16)) {
                    LinearProgressIndicator(
                        progress = if (state.total == 0) 0f else state.completed.toFloat() / state.total,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            if (state.stage == ImportStage.SCANNING) R.string.titanium_scan_progress else R.string.titanium_import_progress,
                            state.completed,
                            state.total,
                        )
                    )
                }
            }
            state.error?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(SizeTokens.Level16),
                )
            }
            if (state.stage == ImportStage.PREVIEW) {
                SelectionActions(
                    selectedCount = selectedCount,
                    onSelectAll = { viewModel.emitIntentOnMain(TitaniumImportIntent.SelectAll(true)) },
                    onUnselectAll = { viewModel.emitIntentOnMain(TitaniumImportIntent.SelectAll(false)) },
                )
            }
            if (state.mode == ImportMode.BACKUPS &&
                (state.stage == ImportStage.PREVIEW || state.stage == ImportStage.IMPORTING || state.stage == ImportStage.COMPLETE)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.emitIntentOnMain(TitaniumImportIntent.SetSearchQuery(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level8),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.search_imports)) },
                    singleLine = true,
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                when {
                    state.stage == ImportStage.PREVIEW && state.mode == ImportMode.BACKUPS -> {
                        val groups = state.backupCandidates
                            .filter { it.matches(state.searchQuery) }
                            .groupBy { it.packageName }.values.sortedBy { it.first().label }
                        items(groups, key = { it.first().packageName }) { candidates ->
                            val packageName = candidates.first().packageName
                            BackupGroupItem(
                                candidates = candidates,
                                selectedIds = state.selectedBackupIds,
                                expanded = packageName in expandedPackages,
                                onToggleGroup = { viewModel.emitIntentOnMain(TitaniumImportIntent.ToggleBackupGroup(packageName)) },
                                onToggleExpanded = {
                                    expandedPackages = if (packageName in expandedPackages) expandedPackages - packageName else expandedPackages + packageName
                                },
                                onToggleBackup = { viewModel.emitIntentOnMain(TitaniumImportIntent.ToggleBackup(it)) },
                            )
                            HorizontalDivider()
                        }
                    }
                    state.stage == ImportStage.PREVIEW -> {
                        items(state.labelCandidates, key = { it.name }) { candidate ->
                            LabelCandidateItem(
                                candidate = candidate,
                                selected = candidate.name in state.selectedLabels,
                                onToggle = { viewModel.emitIntentOnMain(TitaniumImportIntent.ToggleLabel(candidate.name)) },
                            )
                            HorizontalDivider()
                        }
                    }
                    state.stage == ImportStage.IMPORTING && state.mode == ImportMode.BACKUPS -> {
                        val selected = state.backupCandidates.filter { it.id in state.selectedBackupIds }
                        val resultMap = state.backupResults.associateBy { it.key }
                        val processingId = selected.getOrNull(state.backupResults.size)?.id
                        items(selected.filter { it.matches(state.searchQuery) }, key = { it.id }) { candidate ->
                            ImportingBackupItem(candidate, resultMap[candidate.key], candidate.id == processingId)
                            HorizontalDivider()
                        }
                    }
                    state.stage == ImportStage.IMPORTING -> {
                        val resultMap = state.labelResults.associateBy { it.label }
                        val selected = state.labelCandidates.filter { it.name in state.selectedLabels }
                        val processingName = selected.getOrNull(state.labelResults.size)?.name
                        items(selected, key = { it.name }) { candidate ->
                            ImportingLabelItem(candidate, resultMap[candidate.name], candidate.name == processingName)
                            HorizontalDivider()
                        }
                    }
                    state.stage == ImportStage.COMPLETE && state.mode == ImportMode.BACKUPS -> {
                        items(state.backupResults.filter { it.matches(state.searchQuery) }, key = { it.key }) { result ->
                            BackupResultItem(result) {
                                navController.navigateSingle(MainRoutes.AppRevisions.getRoute(result.packageName, 0))
                            }
                            HorizontalDivider()
                        }
                    }
                    state.stage == ImportStage.COMPLETE -> {
                        items(state.labelResults, key = { it.label }) { result ->
                            LabelResultItem(result)
                            HorizontalDivider()
                        }
                    }
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SizeTokens.Level16),
                enabled = !state.cancelling && when (state.stage) {
                    ImportStage.SCANNING, ImportStage.IMPORTING -> true
                    ImportStage.PREVIEW -> selectedCount > 0
                    else -> selectedPath.isNotEmpty()
                },
                onClick = {
                    when (state.stage) {
                        ImportStage.SCANNING, ImportStage.IMPORTING -> {
                            leaveAfterCancel = false
                            showCancelConfirmation = true
                        }
                        ImportStage.PREVIEW -> viewModel.emitIntentOnMain(TitaniumImportIntent.Import)
                        else -> viewModel.emitIntentOnMain(TitaniumImportIntent.Scan)
                    }
                },
            ) {
                Icon(
                    imageVector = when (state.stage) {
                        ImportStage.SCANNING, ImportStage.IMPORTING -> Icons.Outlined.Close
                        ImportStage.PREVIEW -> if (state.mode == ImportMode.BACKUPS) Icons.Outlined.Archive else Icons.AutoMirrored.Outlined.Label
                        else -> Icons.Outlined.Search
                    },
                    contentDescription = null,
                )
                Text(
                    stringResource(
                        when {
                            state.cancelling -> R.string.cancelling
                            state.isBusy -> R.string.cancel
                            state.stage == ImportStage.PREVIEW -> R.string.import_selected
                            else -> R.string.scan_source
                        }
                    )
                )
            }
        }
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = {
                leaveAfterCancel = false
                showCancelConfirmation = false
            },
            title = { Text(stringResource(R.string.cancel_import)) },
            text = { Text(stringResource(R.string.confirm_cancel_import)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.cancelImport()
                    showCancelConfirmation = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    leaveAfterCancel = false
                    showCancelConfirmation = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private val TitaniumImportRepository.BackupCandidate.key: String
    get() = "$packageName:$createdAt"

private val TitaniumImportRepository.BackupResult.key: String
    get() = "$packageName:$createdAt"

private fun TitaniumImportRepository.BackupCandidate.matches(query: String): Boolean = query.isBlank() ||
    label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)

private fun TitaniumImportRepository.BackupResult.matches(query: String): Boolean = query.isBlank() ||
    label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportingBackupItem(
    candidate: TitaniumImportRepository.BackupCandidate,
    result: TitaniumImportRepository.BackupResult?,
    processing: Boolean,
) {
    ListItem(
        leadingContent = { PreviewIcon(candidate) },
        headlineContent = { Text(candidate.label) },
        supportingContent = {
            Column {
                Text("${candidate.versionName} · ${DateUtil.formatTimestamp(candidate.createdAt, DateUtil.PATTERN_YMD_HMS)}")
                if (result != null) ResultDetail(result.status, result.detail)
                else Text(stringResource(if (processing) R.string.importing else R.string.waiting_to_import))
            }
        },
    )
}

@Composable
private fun ImportingLabelItem(
    candidate: TitaniumImportRepository.LabelCandidate,
    result: TitaniumImportRepository.LabelResult?,
    processing: Boolean,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Label,
                contentDescription = null,
                tint = Color(candidate.color),
                modifier = Modifier.size(SizeTokens.Level32),
            )
        },
        headlineContent = { Text(candidate.name) },
        supportingContent = {
            if (result != null) ResultDetail(result.status, result.detail)
            else Text(stringResource(if (processing) R.string.importing else R.string.waiting_to_import))
        },
    )
}

@Composable
private fun ModeButton(selected: Boolean, enabled: Boolean, text: String, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(modifier = modifier, enabled = enabled, onClick = onClick) { Text(text) }
    else OutlinedButton(modifier = modifier, enabled = enabled, onClick = onClick) { Text(text) }
}

@Composable
private fun SelectionActions(selectedCount: Int, onSelectAll: () -> Unit, onUnselectAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
    ) {
        Text(stringResource(R.string.selected_count, selectedCount), modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
        OutlinedButton(onClick = onUnselectAll) { Text(stringResource(R.string.unselect_all)) }
    }
}

@Composable
private fun BackupGroupItem(
    candidates: List<TitaniumImportRepository.BackupCandidate>,
    selectedIds: Set<String>,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleBackup: (String) -> Unit,
) {
    val first = candidates.first()
    val selectableIds = candidates.filter { it.isComplete }.mapTo(mutableSetOf()) { it.id }
    val selected = selectableIds.count(selectedIds::contains)
    val toggleState = when {
        selected == 0 || selectableIds.isEmpty() -> ToggleableState.Off
        selected == selectableIds.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    ListItem(
        leadingContent = { PreviewIcon(first) },
        headlineContent = { Text(first.label) },
        supportingContent = { Text(stringResource(R.string.titanium_backup_versions, candidates.size)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(state = toggleState, enabled = selectableIds.isNotEmpty(), onClick = onToggleGroup)
                IconButton(onClick = onToggleExpanded) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                }
            }
        },
    )
    if (expanded) {
        candidates.forEach { candidate ->
            ListItem(
                modifier = Modifier
                    .padding(start = SizeTokens.Level32)
                    .clickable(enabled = candidate.isComplete) { onToggleBackup(candidate.id) },
                headlineContent = {
                    Text(candidate.versionName.ifEmpty { candidate.versionCode.toString() })
                },
                supportingContent = {
                    Column {
                        Text(DateUtil.formatTimestamp(candidate.createdAt, DateUtil.PATTERN_YMD_HMS))
                        Text(
                            buildList {
                                if (candidate.hasApk) add("APK")
                                if (candidate.hasData) add(stringResource(R.string.titanium_data))
                            }.joinToString(),
                        )
                        Text(
                            text = when {
                                candidate.imported -> stringResource(R.string.already_imported)
                                candidate.isComplete -> stringResource(R.string.ready_to_import)
                                candidate.scanError != null -> candidate.scanError.orEmpty()
                                else -> stringResource(R.string.missing_files, candidate.missingFiles.joinToString())
                            },
                            color = if (candidate.isComplete) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                trailingContent = {
                    Checkbox(
                        checked = candidate.id in selectedIds,
                        enabled = candidate.isComplete,
                        onCheckedChange = { onToggleBackup(candidate.id) },
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewIcon(candidate: TitaniumImportRepository.BackupCandidate) {
    if (candidate.iconPath != null) {
        AsyncImage(model = candidate.iconPath, contentDescription = null, modifier = Modifier.size(SizeTokens.Level48))
    } else {
        PackageIconImage(packageName = candidate.packageName, size = SizeTokens.Level48)
    }
}

@Composable
private fun LabelCandidateItem(candidate: TitaniumImportRepository.LabelCandidate, selected: Boolean, onToggle: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        leadingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Label,
                contentDescription = null,
                tint = Color(candidate.color),
                modifier = Modifier.size(SizeTokens.Level32),
            )
        },
        headlineContent = { Text(candidate.name) },
        supportingContent = {
            Text(
                if (candidate.imported) {
                    "${stringResource(R.string.titanium_label_apps, candidate.packages.size)} · ${stringResource(R.string.already_imported)}"
                } else {
                    stringResource(R.string.titanium_label_apps, candidate.packages.size)
                }
            )
        },
        trailingContent = { Checkbox(checked = selected, onCheckedChange = { onToggle() }) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackupResultItem(result: TitaniumImportRepository.BackupResult, onClick: () -> Unit) {
    ListItem(
        leadingContent = { PackageIconImage(packageName = result.packageName, size = SizeTokens.Level48) },
        headlineContent = { Text(result.label) },
        supportingContent = {
            Column {
                Text("${result.versionName} · ${DateUtil.formatTimestamp(result.createdAt, DateUtil.PATTERN_YMD_HMS)}")
                ResultDetail(result.status, result.detail, result.skippedEntries)
            }
        },
        modifier = Modifier.clickable(enabled = result.status != TitaniumImportRepository.Status.FAILED, onClick = onClick),
    )
}

@Composable
private fun LabelResultItem(result: TitaniumImportRepository.LabelResult) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Label,
                contentDescription = null,
                tint = Color(result.color),
                modifier = Modifier.size(SizeTokens.Level32),
            )
        },
        headlineContent = { Text(result.label) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.titanium_label_apps, result.appCount))
                ResultDetail(result.status, result.detail)
            }
        },
    )
}

@Composable
private fun ResultDetail(status: TitaniumImportRepository.Status, detail: String, skippedEntries: Int = 0) {
    val statusText = when (status) {
        TitaniumImportRepository.Status.IMPORTED -> stringResource(R.string.titanium_imported)
        TitaniumImportRepository.Status.PARTIAL -> stringResource(R.string.titanium_partially_imported)
        TitaniumImportRepository.Status.SKIPPED -> stringResource(R.string.titanium_skipped)
        TitaniumImportRepository.Status.FAILED -> stringResource(R.string.titanium_failed)
    }
    val skippedDetail = if (skippedEntries == 0) "" else stringResource(R.string.titanium_unsafe_entries_skipped, skippedEntries)
    val details = listOf(detail, skippedDetail).filter(String::isNotEmpty).joinToString(" · ")
    Text(
        text = if (details.isEmpty()) statusText else "$statusText · $details",
        color = when (status) {
            TitaniumImportRepository.Status.FAILED -> MaterialTheme.colorScheme.error
            TitaniumImportRepository.Status.PARTIAL -> MaterialTheme.colorScheme.tertiary
            else -> Color.Unspecified
        },
    )
}
