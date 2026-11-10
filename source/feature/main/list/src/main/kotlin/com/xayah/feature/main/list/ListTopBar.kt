package com.xayah.feature.main.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.hiddenapi.castTo
import com.xayah.core.model.OpType
import com.xayah.core.model.UserInfo
import com.xayah.core.ui.component.Divider
import com.xayah.core.ui.component.LinearProgressIndicator
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.PrimaryTopBar
import com.xayah.core.ui.component.SearchBar
import com.xayah.core.ui.component.SecondaryTopBar
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle

@Composable
internal fun ListTopBar(
    isDashboard: Boolean = false,
    viewModel: ListTopBarViewModel = hiltViewModel(),
) {
    val uiState: ListTopBarUiState by viewModel.uiState.collectAsStateWithLifecycle()
    ListTopBar(uiState, isDashboard, viewModel)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ListTopBar(
    uiState: ListTopBarUiState,
    isDashboard: Boolean,
    viewModel: ListTopBarViewModel
) {
    val title: String
    val selected = (uiState as? ListTopBarUiState.Success)?.selected
    when (uiState) {
        is ListTopBarUiState.Loading -> {
            title = stringResource(id = R.string.loading)
        }

        is ListTopBarUiState.Success -> {
            title = when (uiState) {
                is ListTopBarUiState.Success.Apps -> {
                    when (uiState.opType) {
                        OpType.BACKUP -> stringResource(id = R.string.select_apps)

                        OpType.RESTORE -> stringResource(id = R.string.backed_up_apps)
                    }
                }

                is ListTopBarUiState.Success.Files -> {
                    when (uiState.opType) {
                        OpType.BACKUP -> stringResource(id = R.string.select_files)

                        OpType.RESTORE -> stringResource(id = R.string.backed_up_files)
                    }
                }
            }
        }
    }

    Column {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        if (isDashboard) {
            val navController = LocalNavController.current!!
            PrimaryTopBar(
                scrollBehavior = scrollBehavior,
                title = selected?.let { stringResource(R.string.dashboard_selection_title, it) } ?: stringResource(R.string.app_name),
                titleModifier = if (selected != null) Modifier.basicMarquee() else Modifier,
                actions = {
                    ListActions()
                    IconButton(
                        icon = Icons.Outlined.Settings,
                        tooltip = stringResource(R.string.settings),
                        onClick = { navController.navigateSingle(MainRoutes.Settings.route) },
                    )
                },
            )
        } else {
            SecondaryTopBar(
                scrollBehavior = scrollBehavior,
                title = title,
                subtitle = selected?.let { stringResource(R.string.selected_count, it) },
                actions = { ListActions() },
            )
        }

        AnimatedVisibility(visible = (uiState is ListTopBarUiState.Loading) || (uiState is ListTopBarUiState.Success && uiState.isUpdating)) {
            val progress = (uiState as? ListTopBarUiState.Success)?.refreshProgress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }
        }

        if (uiState is ListTopBarUiState.Success) {
            SearchBar(
                modifier = Modifier
                    .paddingHorizontal(SizeTokens.Level16)
                    .paddingVertical(SizeTokens.Level8),
                enabled = true,
                placeholder = when (uiState) {
                    is ListTopBarUiState.Success.Apps -> stringResource(
                        id = if (isDashboard) R.string.search_bar_hint_dashboard else R.string.search_bar_hint_packages
                    )
                    is ListTopBarUiState.Success.Files -> stringResource(id = R.string.search_bar_hint_medium)
                },
                onTextChange = viewModel::search
            )
        }

        AnimatedVisibility(visible = uiState is ListTopBarUiState.Success.Apps) {
            val state = uiState.castTo<ListTopBarUiState.Success.Apps>()
            UserTabs(selected = state.userIndex, userList = state.userList, onTabClick = viewModel::setUser)
        }

        AnimatedVisibility(visible = uiState is ListTopBarUiState.Success.Files) {
            Divider(modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserTabs(selected: Int, userList: List<UserInfo>, onTabClick: (userId: Int) -> Unit) {
    if (userList.isNotEmpty()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selected,
            edgePadding = SizeTokens.Level0,
            indicator = @Composable {
                TabRowDefaults.PrimaryIndicator(
                    Modifier.tabIndicatorOffset(selected, matchContentSize = true),
                    shape = CircleShape
                )
            },
            divider = {
                Divider(modifier = Modifier.fillMaxWidth())
            }
        ) {
            userList.forEachIndexed { index, user ->
                Tab(
                    selected = selected == index,
                    onClick = {
                        onTabClick(user.id)
                    },
                    text = {
                        Text(text = "${user.name} (${user.id})", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                )
            }
        }
    }
}
