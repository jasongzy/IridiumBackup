package com.xayah.feature.main.configurations

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.Checkable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.util.joinOf

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageConfigurations() {
    val dialogState = LocalSlotScope.current!!.dialogSlot
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    ConfigurationsScaffold(
        scrollBehavior = scrollBehavior, snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.configurations), actions = {
            OutlinedButton(
                enabled = counts?.let {
                    uiState.settingsSelected || (uiState.blacklistSelected && it.blacklist > 0) ||
                        (uiState.cloudSelected && it.accounts > 0) || (uiState.labelSelected && it.labels > 0) ||
                        (uiState.appNotesSelected && it.appNotes > 0)
                } == true,
                onClick = { viewModel.emitIntentOnIO(IndexUiIntent.Export) },
            ) {
                Text(
                    text = joinOf(
                        stringResource(id = R.string.export),
                        " (${uiState.selectedCount})",
                    )
                )
            }
            Button(enabled = true, onClick = {
                viewModel.emitIntentOnIO(IndexUiIntent.Import(dialogState))
            }) {
                Text(text = stringResource(id = R.string._import))
            }
        }
    ) {
        Checkable(
            icon = Icons.Outlined.Block,
            title = stringResource(id = R.string.blacklist),
            value = counts?.blacklist?.toString(),
            checked = uiState.blacklistSelected,
        ) {
            viewModel.emitStateOnMain(uiState.copy(blacklistSelected = it.not()))
        }
        Checkable(
            icon = Icons.Outlined.Cloud,
            title = stringResource(id = R.string.cloud_accounts),
            value = counts?.accounts?.toString(),
            checked = uiState.cloudSelected,
        ) {
            viewModel.emitStateOnMain(uiState.copy(cloudSelected = it.not()))
        }
        Checkable(
            icon = Icons.Outlined.BookmarkBorder,
            title = stringResource(id = R.string.labels),
            value = counts?.labels?.toString(),
            checked = uiState.labelSelected,
        ) {
            viewModel.emitStateOnMain(uiState.copy(labelSelected = it.not()))
        }
        Checkable(
            icon = Icons.AutoMirrored.Outlined.Notes,
            title = stringResource(id = R.string.app_notes),
            value = counts?.appNotes?.toString(),
            checked = uiState.appNotesSelected,
        ) {
            viewModel.emitStateOnMain(uiState.copy(appNotesSelected = it.not()))
        }
        Checkable(
            icon = Icons.Outlined.Settings,
            title = stringResource(id = R.string.other_settings),
            checked = uiState.settingsSelected,
        ) {
            viewModel.emitStateOnMain(uiState.copy(settingsSelected = it.not()))
        }
    }
}
