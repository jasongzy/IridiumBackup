package com.xayah.feature.main.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.OpType
import com.xayah.core.model.AppKey
import com.xayah.core.model.Target
import com.xayah.core.ui.component.DropdownMenuItem
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.ModalDropdownMenu
import com.xayah.core.ui.component.confirm
import com.xayah.core.ui.token.SizeTokens
import com.xayah.libpickyou.PickYouLauncher
import com.xayah.libpickyou.ui.model.PermissionType
import com.xayah.libpickyou.ui.model.PickerType

@Composable
internal fun ListActions(
    viewModel: ListActionsViewModel = hiltViewModel(),
    itemsViewModel: ListItemsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val itemsUiState by itemsViewModel.uiState.collectAsStateWithLifecycle()
    val visibleAppKeys = (itemsUiState as? ListItemsUiState.Success.Apps)
        ?.appList
        ?.map { it.app.key }
        .orEmpty()

    ListActions(uiState, viewModel, visibleAppKeys)
}

@Composable
internal fun ListActions(
    uiState: ListActionsUiState,
    viewModel: ListActionsViewModel,
    visibleAppKeys: List<AppKey>,
) {
    if (uiState is ListActionsUiState.Success) {
        val context = LocalContext.current
        val target by remember(uiState) {
            mutableStateOf(
                when (uiState) {
                    is ListActionsUiState.Success.Apps -> Target.Apps
                    is ListActionsUiState.Success.Files -> Target.Files
                }
            )
        }

        FilterAction(viewModel::showFilterSheet)
        var sortExpanded by rememberSaveable { mutableStateOf(false) }
        SortAction { sortExpanded = true }
        SortSheet(
            isShow = sortExpanded,
            target = target,
            selected = uiState.sortIndex,
            sortType = uiState.sortType,
            onSortByType = viewModel::setSortByType,
            onSortByIndex = viewModel::setSortByIndex,
            onDismissRequest = { sortExpanded = false },
        )

        if (uiState.selectionMode) {
            ListAction(
                viewModel = viewModel,
                visibleAppKeys = visibleAppKeys,
                rangeSelectionEnabled = target == Target.Apps && uiState.selected >= 2,
            )
        }

        var moreExpanded by remember { mutableStateOf(false) }
        var labelsExpanded by rememberSaveable { mutableStateOf(false) }
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            IconButton(icon = Icons.Rounded.MoreVert, tooltip = stringResource(R.string.more_options)) {
                moreExpanded = true
            }
            ModalDropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false }
            ) {
                if (uiState.selectionMode.not()) {
                    RefreshItem(enabled = uiState.isUpdating.not()) {
                        moreExpanded = false
                        viewModel.refresh()
                    }
                    if (target == Target.Files && uiState.opType == OpType.BACKUP) {
                        AddItem(enabled = uiState.isUpdating.not()) {
                            moreExpanded = false
                            PickYouLauncher(
                                checkPermission = true,
                                title = context.getString(R.string.select_target_directory),
                                pickerType = PickerType.DIRECTORY,
                                permissionType = PermissionType.ROOT,
                            ).apply { launch(context) { viewModel.addFiles(listOf(it)) } }
                        }
                    }
                } else when (target) {
                    Target.Apps -> {
                        AppsListActions(
                            enabled = true,
                            opType = uiState.opType,
                            checkListExpanded = { moreExpanded = false },
                            onBlockSelected = viewModel::blockSelected,
                            onSelectDataItems = viewModel::showDataItemsSheet,
                            onDeleteSelected = viewModel::deleteSelected,
                        )
                        if (uiState is ListActionsUiState.Success.Apps) {
                            BatchLabelsItem {
                                moreExpanded = false
                                labelsExpanded = true
                            }
                        }
                    }

                    Target.Files -> FilesListActions(
                        enabled = true,
                        opType = uiState.opType,
                        checkListExpanded = { moreExpanded = false },
                        onBlockSelected = viewModel::blockSelected,
                        onDeleteSelected = viewModel::deleteSelected,
                    )
                }
            }
        }

        if (labelsExpanded && uiState is ListActionsUiState.Success.Apps) {
            BatchLabelsDialog(
                labels = uiState.labels.map { it.label },
                onDismiss = { labelsExpanded = false },
                onCreate = viewModel::createLabel,
                onAdd = {
                    viewModel.addLabelsToSelected(it)
                    labelsExpanded = false
                },
                onRemove = {
                    viewModel.removeLabelsFromSelected(it)
                    labelsExpanded = false
                },
            )
        }
    }
}

@Composable
private fun FilterAction(onFilter: () -> Unit) {
    IconButton(icon = Icons.Outlined.FilterList, tooltip = stringResource(R.string.filters), onClick = onFilter)
}

@Composable
private fun SortAction(onSort: () -> Unit) {
    IconButton(icon = Icons.AutoMirrored.Rounded.Sort, tooltip = stringResource(R.string.sort), onClick = onSort)
}

@Composable
private fun ListAction(
    viewModel: ListActionsViewModel,
    visibleAppKeys: List<AppKey>,
    rangeSelectionEnabled: Boolean,
) {
    var checkListExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        IconButton(icon = Icons.Rounded.Checklist, tooltip = stringResource(R.string.selection_options)) {
            checkListExpanded = true
        }
        ModalDropdownMenu(
            expanded = checkListExpanded,
            onDismissRequest = { checkListExpanded = false }
        ) {
            SelectAllItem {
                checkListExpanded = false
                viewModel.selectAll(visibleAppKeys)
            }
            UnselectAllItem {
                checkListExpanded = false
                viewModel.unselectAll(visibleAppKeys)
            }
            ReverseItem {
                checkListExpanded = false
                viewModel.reverseAll(visibleAppKeys)
            }
            SelectRangeItem(enabled = rangeSelectionEnabled) {
                checkListExpanded = false
                viewModel.selectRange(visibleAppKeys)
            }
        }
    }
}

@Composable
private fun AppsListActions(
    enabled: Boolean,
    opType: OpType,
    checkListExpanded: () -> Unit,
    onBlockSelected: () -> Unit,
    onSelectDataItems: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val context = LocalContext.current
    val dialogState = LocalSlotScope.current!!.dialogSlot

    when (opType) {
        OpType.BACKUP -> {
            BlockItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_add_to_blacklist)
                ) {
                    onBlockSelected()
                }
            }
            DetailedDataItem(enabled) {
                checkListExpanded()
                onSelectDataItems()
            }
        }

        OpType.RESTORE -> {
            DeleteItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_delete)
                ) {
                    onDeleteSelected()
                }
            }
            DetailedDataItem(enabled) {
                checkListExpanded()
                onSelectDataItems()
            }
        }
    }
}

@Composable
private fun FilesListActions(
    enabled: Boolean,
    opType: OpType,
    checkListExpanded: () -> Unit,
    onBlockSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val context = LocalContext.current
    val dialogState = LocalSlotScope.current!!.dialogSlot

    when (opType) {
        OpType.BACKUP -> {
            BlockItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_add_to_blacklist)
                ) {
                    onBlockSelected()
                }
            }
            DeleteItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_delete)
                ) {
                    onDeleteSelected()
                }
            }
        }

        OpType.RESTORE -> {
            DeleteItem(enabled) {
                checkListExpanded()
                dialogState.confirm(
                    title = context.getString(R.string.prompt),
                    text = context.getString(R.string.confirm_delete)
                ) {
                    onDeleteSelected()
                }
            }
        }
    }
}

@Composable
private fun SelectAllItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.select_all),
        leadingIcon = Icons.Rounded.CheckBox,
        onClick = onClick,
    )
}

@Composable
private fun UnselectAllItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.unselect_all),
        leadingIcon = Icons.Rounded.CheckBoxOutlineBlank,
        onClick = onClick,
    )
}

@Composable
private fun ReverseItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.reverse_selection),
        leadingIcon = Icons.Rounded.RestartAlt,
        onClick = onClick,
    )
}

@Composable
private fun SelectRangeItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.select_range),
        leadingIcon = Icons.Rounded.LinearScale,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun BlockItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.block),
        leadingIcon = Icons.Rounded.Block,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun DetailedDataItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.detailed_data_items),
        leadingIcon = Icons.AutoMirrored.Rounded.Rule,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun DeleteItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.delete),
        leadingIcon = Icons.Rounded.Delete,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun RefreshItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.refresh),
        leadingIcon = Icons.Rounded.Refresh,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun AddItem(enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(id = R.string.add),
        leadingIcon = Icons.Rounded.Add,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun BatchLabelsItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = stringResource(R.string.edit_labels),
        leadingIcon = Icons.Rounded.Bookmarks,
        onClick = onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatchLabelsDialog(
    labels: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onAdd: (Set<String>) -> Unit,
    onRemove: (Set<String>) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var newLabel by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf<Boolean?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_labels)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text(stringResource(R.string.new_label)) },
                    singleLine = true,
                    trailingIcon = {
                        com.xayah.core.ui.component.TooltipIconButton(
                            tooltip = stringResource(R.string.add),
                            enabled = newLabel.isNotBlank(),
                            onClick = {
                                onCreate(newLabel)
                                newLabel = ""
                            },
                        ) { Icon(Icons.Rounded.Add, contentDescription = null) }
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                    labels.forEach { label ->
                        FilterChip(
                            selected = label in selected,
                            onClick = {
                                selected = if (label in selected) selected - label else selected + label
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { confirmation = true }) {
                Text(stringResource(R.string.add_label_to_selected))
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(enabled = selected.isNotEmpty(), onClick = { confirmation = false }) {
                    Text(stringResource(R.string.remove_label_from_selected))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    confirmation?.let { adding ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.edit_labels)) },
            text = {
                Text(stringResource(if (adding) R.string.confirm_add_labels else R.string.confirm_remove_labels, selected.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (adding) onAdd(selected) else onRemove(selected)
                    confirmation = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
