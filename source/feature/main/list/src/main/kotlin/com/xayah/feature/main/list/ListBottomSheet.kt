package com.xayah.feature.main.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.data.repository.Filters
import com.xayah.core.data.repository.LabelFilterMode
import com.xayah.core.datastore.readLoadSystemApps
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.SortType
import com.xayah.core.model.Target
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.ColoredLabel
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStates.Companion.setSelected
import com.xayah.core.ui.component.BottomButton
import com.xayah.core.ui.component.DataChips
import com.xayah.core.ui.component.PackageDataStatesSaver
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.ModalBottomSheet
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.TitleLargeText
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.util.localBackupSaveDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ListBottomSheet(
    viewModel: ListBottomSheetViewModel = hiltViewModel(),
) {
    val uiState: ListBottomSheetUiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState is ListBottomSheetUiState.Success) {
        ListBottomSheet(
            uiState = uiState.castTo(),
            viewModel = viewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListBottomSheet(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    uiState: ListBottomSheetUiState.Success,
    viewModel: ListBottomSheetViewModel,
) {
    val sheetState = rememberModalBottomSheetState()
    val onDismissRequest: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            if (!sheetState.isVisible) {
                viewModel.setShowFilterSheet(false)
            }
        }
    }

    when (uiState) {
        is ListBottomSheetUiState.Success.Apps -> {
            AppsFilterSheet(
                isShow = uiState.showFilterSheet,
                sheetState = sheetState,
                opType = uiState.opType,
                clouds = uiState.clouds,
                filters = uiState.filters,
                labelEntities = uiState.labelEntities,
                labelFilters = uiState.labelFilters,
                onClickLabel = viewModel::cycleLabelFilter,
                onLongClickLabel = viewModel::resetLabelFilter,
                setFilters = viewModel::setFilters,
                onReset = viewModel::resetFilters,
                onDismissRequest = onDismissRequest,
            )

            val dataItemsSheetState = rememberModalBottomSheetState()
            AppsDataItemsSheet(
                isShow = uiState.showDataItemsSheet,
                sheetState = dataItemsSheetState,
                onDismissRequest = {
                    coroutineScope.launch {
                        dataItemsSheetState.hide()
                        if (!dataItemsSheetState.isVisible) {
                            viewModel.setShowDataItemsSheet(false)
                        }
                    }
                },
                onSetDataItems = {
                    viewModel.setDataItems(it)
                }
            )
        }

        is ListBottomSheetUiState.Success.Files -> {
            FilesFilterSheet(
                isShow = uiState.showFilterSheet,
                sheetState = sheetState,
                labelEntities = uiState.labelEntities,
                labelFilters = uiState.labelFilters,
                onClickLabel = viewModel::cycleLabelFilter,
                onLongClickLabel = viewModel::resetLabelFilter,
                onDismissRequest = onDismissRequest,
            )
        }
    }
}

@Composable
private fun SourceChips(clouds: List<CloudEntity>, selectedCloud: String, onChanged: (cloud: String, backupDir: String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .paddingHorizontal(SizeTokens.Level24)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
    ) {
        val context = LocalContext.current
        FilterChip(
            onClick = {
                onChanged("", context.localBackupSaveDir())
            },
            label = { Text(stringResource(R.string.local)) },
            selected = selectedCloud.isEmpty(),
            leadingIcon = if (selectedCloud.isEmpty()) {
                {
                    Icon(
                        imageVector = Icons.Filled.Done,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            } else {
                null
            },
        )

        clouds.forEach { cloudEntity ->
            FilterChip(
                onClick = {
                    onChanged(cloudEntity.name, cloudEntity.remote)
                },
                label = { Text(cloudEntity.name) },
                selected = selectedCloud == cloudEntity.name,
                leadingIcon = if (selectedCloud == cloudEntity.name) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                } else {
                    null
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelsFlow(
    labelEntities: List<ColoredLabel>,
    labelFilters: Map<String, LabelFilterMode>,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .paddingHorizontal(SizeTokens.Level24),
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4)
    ) {
        labelEntities.forEach { item ->
            val mode = labelFilters[item.label]
            val color = Color(item.colorArgb)
            FilterChip(
                modifier = Modifier.pointerInput(item.label) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        awaitLongPressOrCancellation(down.id)?.let { change ->
                            change.consume()
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick(item.label)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                },
                onClick = { onClick(item.label) },
                label = {
                    Text(
                        when (mode) {
                            LabelFilterMode.INCLUDE -> "+ ${item.label}"
                            LabelFilterMode.EXCLUDE -> "− ${item.label}"
                            null -> item.label
                        }
                    )
                },
                selected = mode != null,
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = color,
                    selectedContainerColor = color.copy(alpha = 0.16f),
                    selectedLabelColor = color,
                    selectedLeadingIconColor = color,
                ),
                leadingIcon = if (mode != null) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                } else null,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactOptions(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().paddingHorizontal(SizeTokens.Level24),
        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
    ) { content() }
}

@Composable
private fun CompactOption(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, selected: Int, options: List<String>, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SizeTokens.Level4)
            .paddingHorizontal(SizeTokens.Level24),
        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
        ) {
            options.forEachIndexed { index, option ->
                CompactOption(text = option, selected = selected == index, onClick = { onSelect(index) })
            }
        }
    }
}

private fun filterIndex(first: Boolean, second: Boolean) = when {
    first && !second -> 1
    !first && second -> 2
    else -> 0
}

private fun categorySelection(index: Int) = when (index) {
    1 -> true to false
    2 -> false to true
    else -> true to true
}

private fun optionalSelection(index: Int) = when (index) {
    1 -> true to false
    2 -> false to true
    else -> false to false
}

@Composable
private fun SortOptions(
    selected: Int,
    sortType: SortType,
    items: List<String>,
    onSelect: (Int) -> Unit,
    onToggle: () -> Unit,
) {
    CompactOptions {
        items.forEachIndexed { index, item ->
            val isSelected = selected == index
            FilterChip(
                selected = isSelected,
                onClick = { if (isSelected) onToggle() else onSelect(index) },
                label = { Text(item) },
                trailingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = if (sortType == SortType.ASCENDING) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsFilterSheet(
    isShow: Boolean,
    sheetState: SheetState,
    opType: OpType,
    clouds: List<CloudEntity>,
    filters: Filters,
    labelEntities: List<ColoredLabel>,
    labelFilters: Map<String, LabelFilterMode>,
    onClickLabel: (String) -> Unit,
    onLongClickLabel: (String) -> Unit,
    setFilters: (Filters) -> Unit,
    onReset: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val loadSystemApps by LocalContext.current.readLoadSystemApps().collectAsStateWithLifecycle(initialValue = filters.systemApps)
    if (isShow) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = SizeTokens.Level24, end = SizeTokens.Level12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TitleLargeText(modifier = Modifier.weight(1f), text = stringResource(R.string.filters))
                IconButton(
                    icon = Icons.Rounded.RestartAlt,
                    tooltip = stringResource(R.string.reset_filters),
                    onClick = onReset,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                if (opType == OpType.BACKUP) {
                    SourceChips(clouds, filters.cloud) { cloud, backupDir ->
                        setFilters(filters.copy(cloud = cloud, backupDir = backupDir))
                    }
                }
                if (loadSystemApps) {
                    FilterGroup(
                        title = stringResource(R.string.app_type),
                        selected = filterIndex(filters.systemApps, filters.nonSystemApps),
                        options = listOf(
                            stringResource(R.string.unrestricted),
                            stringResource(R.string.system_apps),
                            stringResource(R.string.non_system_apps),
                        ),
                    ) { index ->
                        val (systemApps, nonSystemApps) = categorySelection(index)
                        setFilters(filters.copy(systemApps = systemApps, nonSystemApps = nonSystemApps))
                    }
                }
                FilterGroup(
                    title = stringResource(R.string.installation_status),
                    selected = filterIndex(filters.installedApps, filters.notInstalledApps),
                    options = listOf(
                        stringResource(R.string.unrestricted),
                        stringResource(R.string.installed),
                        stringResource(R.string.not_installed),
                    ),
                ) { index ->
                    val (installedApps, notInstalledApps) = categorySelection(index)
                    setFilters(filters.copy(installedApps = installedApps, notInstalledApps = notInstalledApps))
                }
                FilterGroup(
                    title = stringResource(R.string.module_type),
                    selected = filterIndex(filters.xposedModules, filters.nonXposedModules),
                    options = listOf(
                        stringResource(R.string.unrestricted),
                        stringResource(R.string.xposed),
                        stringResource(R.string.non_xposed),
                    ),
                ) { index ->
                    val (xposedModules, nonXposedModules) = optionalSelection(index)
                    setFilters(filters.copy(xposedModules = xposedModules, nonXposedModules = nonXposedModules))
                }
                FilterGroup(
                    title = stringResource(R.string.freeze_status),
                    selected = filterIndex(filters.frozenApps, filters.unfrozenApps),
                    options = listOf(
                        stringResource(R.string.unrestricted),
                        stringResource(R.string.frozen_apps),
                        stringResource(R.string.unfrozen_apps),
                    ),
                ) { index ->
                    val (frozenApps, unfrozenApps) = categorySelection(index)
                    setFilters(filters.copy(frozenApps = frozenApps, unfrozenApps = unfrozenApps))
                }
                if (opType == OpType.BACKUP) {
                    FilterGroup(
                        title = stringResource(R.string.backup_count),
                        selected = when {
                            filters.singleBackup -> 3
                            filters.multipleBackups -> 4
                            filters.hasBackups && !filters.hasNoBackups -> 1
                            !filters.hasBackups && filters.hasNoBackups -> 2
                            else -> 0
                        },
                        options = listOf(
                            stringResource(R.string.unrestricted),
                            stringResource(R.string.apps_which_have_backups),
                            stringResource(R.string.apps_which_have_no_backups),
                            stringResource(R.string.one_backup),
                            stringResource(R.string.multiple_backups),
                        ),
                    ) { index ->
                        setFilters(
                            filters.copy(
                                hasBackups = index != 2,
                                hasNoBackups = index == 0 || index == 2,
                                singleBackup = index == 3,
                                multipleBackups = index == 4,
                            )
                        )
                    }
                    FilterGroup(
                        title = stringResource(R.string.apk_backup),
                        selected = when {
                            filters.hasNonMatchingApkBackup -> 3
                            filters.hasApkBackup -> 1
                            filters.hasNoApkBackup -> 2
                            else -> 0
                        },
                        options = listOf(
                            stringResource(R.string.unrestricted),
                            stringResource(R.string.has_apk_backup),
                            stringResource(R.string.has_no_apk_backup),
                            stringResource(R.string.has_non_matching_apk_backup),
                        ),
                    ) { index ->
                        setFilters(
                            filters.copy(
                                hasApkBackup = index == 1,
                                hasNoApkBackup = index == 2,
                                hasNonMatchingApkBackup = index == 3,
                            )
                        )
                    }
                    FilterGroup(
                        title = stringResource(R.string.data_backup),
                        selected = filterIndex(filters.hasDataBackup, filters.hasNoDataBackup),
                        options = listOf(
                            stringResource(R.string.unrestricted),
                            stringResource(R.string.has_data_backup),
                            stringResource(R.string.has_no_data_backup),
                        ),
                    ) { index ->
                        val (hasDataBackup, hasNoDataBackup) = optionalSelection(index)
                        setFilters(filters.copy(hasDataBackup = hasDataBackup, hasNoDataBackup = hasNoDataBackup))
                    }
                }

                if (labelEntities.isNotEmpty()) {
                    Title(text = stringResource(id = R.string.labels))
                    FilterGroup(
                        title = stringResource(R.string.include_matching),
                        selected = if (filters.matchAllLabels) 1 else 0,
                        options = listOf(
                            stringResource(R.string.match_any),
                            stringResource(R.string.match_all),
                        ),
                    ) { index ->
                        setFilters(filters.copy(matchAllLabels = index == 1))
                    }
                    LabelsFlow(
                        labelEntities = labelEntities,
                        labelFilters = labelFilters,
                        onClick = onClickLabel,
                        onLongClick = onLongClickLabel,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesFilterSheet(
    isShow: Boolean,
    sheetState: SheetState,
    labelEntities: List<ColoredLabel>,
    labelFilters: Map<String, LabelFilterMode>,
    onClickLabel: (String) -> Unit,
    onLongClickLabel: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (isShow) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
            if (labelEntities.isNotEmpty()) {
                Title(text = stringResource(id = R.string.labels))
                LabelsFlow(
                    labelEntities = labelEntities,
                    labelFilters = labelFilters,
                    onClick = onClickLabel,
                    onLongClick = onLongClickLabel,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SortSheet(
    isShow: Boolean,
    target: Target,
    selected: Int,
    sortType: SortType,
    onSortByType: () -> Unit,
    onSortByIndex: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (isShow) {
        ModalBottomSheet(onDismissRequest = onDismissRequest) {
            Title(text = stringResource(R.string.sort))
            SortOptions(
                selected = selected,
                sortType = sortType,
                items = stringArrayResource(
                    if (target == Target.Apps) R.array.backup_sort_type_items_apps else R.array.backup_sort_type_items_files
                ).toList(),
                onSelect = onSortByIndex,
                onToggle = onSortByType,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsDataItemsSheet(
    isShow: Boolean,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onSetDataItems: (PackageDataStates) -> Unit,
) {
    if (isShow) {
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
            Title(text = stringResource(id = R.string.data_items))

            var selections by rememberSaveable(stateSaver = PackageDataStatesSaver) { mutableStateOf(PackageDataStates()) }
            DataChips(selections) { type, selected ->
                selections = type.setSelected(selections, selected.not())
            }

            BottomButton(text = stringResource(id = R.string.confirm)) {
                onDismissRequest()
                onSetDataItems(selections)
            }
        }
    }
}
