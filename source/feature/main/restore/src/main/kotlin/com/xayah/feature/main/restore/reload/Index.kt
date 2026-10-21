package com.xayah.feature.main.restore.reload

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.LinearProgressIndicator
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.restore.R
import com.xayah.feature.main.restore.RestoreScaffold
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageReload() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    var showInfo by remember { mutableStateOf(false) }

    RestoreScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.reload),
        topActions = {
            IconButton(
                icon = Icons.Outlined.Info,
                tooltip = stringResource(id = R.string.rebuild_rules),
                onClick = { showInfo = true },
            )
        },
        actions = {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.isLoading.not(),
                onClick = {
                    viewModel.emitIntentOnMain(IndexUiIntent.Rebuild)
                },
            ) {
                Text(stringResource(R.string.reload))
            }
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .paddingTop(SizeTokens.Level16),
                    contentAlignment = Alignment.Center
                ) {
                    Column(modifier = Modifier.paddingHorizontal(SizeTokens.Level16), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (uiState.isLoading) {
                            if (uiState.total == 0) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = uiState.completed.toFloat() / uiState.total,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.rebuild_progress,
                                    uiState.completed,
                                    uiState.total,
                                    uiState.indexed,
                                ),
                                modifier = Modifier.paddingTop(SizeTokens.Level16),
                            )
                        } else if (uiState.results.isEmpty()) {
                            SelectionContainer { Text(uiState.text) }
                        }
                    }
                }
            }
            items(uiState.results, key = { it.revision.id }) { result ->
                val revision = result.revision
                ListItem(
                    leadingContent = { PackageIconImage(packageName = revision.packageName, size = SizeTokens.Level48) },
                    headlineContent = { Text(result.appLabel) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.rebuild_item_details,
                                revision.appVersionName,
                                revision.appVersionCode,
                                DateUtil.formatTimestamp(revision.createdAt, DateUtil.PATTERN_YMD_HMS),
                            )
                        )
                    },
                    modifier = Modifier
                        .padding(horizontal = SizeTokens.Level8)
                        .clickable {
                            navController.navigateSingle(
                                MainRoutes.AppRevisions.getRoute(revision.packageName, revision.userId)
                            )
                        },
                )
                HorizontalDivider()
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.rebuild_rules)) },
            text = { Text(stringResource(R.string.rebuild_rules_desc)) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}
