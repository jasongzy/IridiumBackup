package com.xayah.feature.main.list

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.CircularProgressIndicator
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.token.SizeTokens

@Composable
fun ListRoute(
    viewModel: ListViewModel = hiltViewModel(),
    isDashboard: Boolean = false,
) {
    val navController = LocalNavController.current!!
    val uiState: ListUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionMode = (uiState as? ListUiState.Success)?.selectionMode == true
    BackHandler(
        enabled = selectionMode,
        onBack = viewModel::clearSelection,
    )
    LaunchedEffect(isDashboard) {
        if (isDashboard) viewModel.refresh(initial = true)
    }
    ListScreen(
        uiState = uiState,
        isDashboard = isDashboard,
        onRefresh = viewModel::refresh,
        onBackup = { viewModel.toNextPage(navController) },
        onRestore = { viewModel.restoreSelected(navController) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListScreen(
    uiState: ListUiState,
    isDashboard: Boolean,
    onRefresh: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val scrollState = rememberLazyListState()
    val selectionMode = uiState is ListUiState.Success && uiState.selectionMode

    ListBottomSheet()

    Scaffold(
        topBar = { ListTopBar(isDashboard = isDashboard) },
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = {
            val appsState = uiState as? ListUiState.Success.Apps
            AnimatedVisibility(
                visible = appsState?.let { it.hasSelectedInstalledApps || it.hasSelectedBackups } == true ||
                    (uiState is ListUiState.Success.Files && uiState.selected != 0L),
                enter = scaleIn(),
                exit = scaleOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12)) {
                    if (appsState?.isPreparing == true) {
                        ExtendedFloatingActionButton(
                            onClick = {},
                            icon = { CircularProgressIndicator(Modifier.size(SizeTokens.Level24)) },
                            text = { Text(stringResource(R.string.loading)) },
                        )
                    } else if (appsState?.hasSelectedBackups == true) {
                        ExtendedFloatingActionButton(
                            onClick = onRestore,
                            icon = { Icon(Icons.Rounded.Restore, null) },
                            text = { Text(text = stringResource(id = R.string.restore)) },
                        )
                    }
                    if (appsState?.isPreparing != true && (appsState?.hasSelectedInstalledApps == true || uiState is ListUiState.Success.Files)) {
                        ExtendedFloatingActionButton(
                            onClick = onBackup,
                            icon = { Icon(Icons.Rounded.Backup, null) },
                            text = { Text(text = stringResource(id = R.string.back_up_selected)) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column {
            InnerTopSpacer(innerPadding = innerPadding)

            PullToRefreshBox(
                modifier = Modifier.weight(1f),
                isRefreshing = (uiState as? ListUiState.Success)?.isUpdating == true,
                onRefresh = onRefresh,
            ) {
                ListItems(scrollState = scrollState, selectionMode = selectionMode)
            }
        }
    }
}
