package com.xayah.feature.main.directory

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.ContentWithActions
import com.xayah.core.ui.component.LocalPathPicker
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.model.getActionMenuDeleteItem
import com.xayah.core.ui.model.getActionMenuReturnItem
import com.xayah.core.ui.token.SizeTokens

@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageDirectory() {
    val context = LocalContext.current
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val internalDirectoriesState by viewModel.internalDirectoriesState.collectAsStateWithLifecycle()
    val externalDirectoriesState by viewModel.externalDirectoriesState.collectAsStateWithLifecycle()
    val customDirectoriesState by viewModel.customDirectoriesState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var pickingDirectory by rememberSaveable { mutableStateOf(false) }

    if (pickingDirectory) {
        LocalPathPicker(
            title = stringResource(R.string.select_target_directory),
            allowCreateDirectory = true,
            onDismiss = { pickingDirectory = false },
            onSelect = {
                pickingDirectory = false
                viewModel.emitIntentOnIO(IndexUiIntent.Add(it))
            },
        )
    }

    LaunchedEffect(null) {
        viewModel.emitIntentOnIO(IndexUiIntent.Update)
    }

    DirectoryScaffold(
        scrollBehavior = scrollBehavior,
        isLoading = uiState.updating,
        title = stringResource(id = R.string.backup_dir),
        actions = {}
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .paddingHorizontal(SizeTokens.Level16),
            verticalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            item {
                Spacer(modifier = Modifier.size(SizeTokens.Level0))
            }

            items(items = internalDirectoriesState) { item ->
                DirectoryCard(item = item) {
                    viewModel.emitIntentOnIO(IndexUiIntent.Select(entity = item))
                }
            }

            items(items = externalDirectoriesState) { item ->
                DirectoryCard(item = item) {
                    viewModel.emitIntentOnIO(IndexUiIntent.Select(entity = item))
                }
            }

            items(items = customDirectoriesState) { item ->
                ContentWithActions(
                    actions = { expanded ->
                        listOf(
                            getActionMenuReturnItem(context) { expanded.value = false },
                            getActionMenuDeleteItem(context) {
                                viewModel.emitIntent(IndexUiIntent.Delete(entity = item))
                                expanded.value = false
                            }
                        )
                    },
                ) {
                    DirectoryCard(item = item, performHapticFeedback = true, onLongClick = { it.value = true }) {
                        viewModel.emitIntentOnIO(IndexUiIntent.Select(entity = item))
                    }
                }
            }

            item {
                CustomDirectoryCard(enabled = uiState.updating.not()) {
                    pickingDirectory = true
                }
            }

            item {
                Spacer(modifier = Modifier.size(SizeTokens.Level0))
            }
        }
    }
}
