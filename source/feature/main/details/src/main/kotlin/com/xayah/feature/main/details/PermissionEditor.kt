package com.xayah.feature.main.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.ui.component.InnerBottomSpacer
import com.xayah.core.ui.component.InnerTopSpacer
import com.xayah.core.ui.component.SecondaryLargeTopBar
import com.xayah.core.ui.component.SetOnResume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionEditorRoute(
    viewModel: DetailsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updatingPermission by viewModel.updatingPermission.collectAsStateWithLifecycle()
    SetOnResume { viewModel.refreshPermissions() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val app = (uiState as? DetailsUiState.Success.App)?.app
    val permissions = app?.extraInfo?.permissions.orEmpty()
        .distinctBy { it.name }
        .sortedBy { it.name }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SecondaryLargeTopBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.permission_editor),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize()) {
            InnerTopSpacer(innerPadding)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(permissions, key = { it.name }) { permission ->
                    val granted = permission.isAllowed
                    val enabled = !updatingPermission
                    ListItem(
                        modifier = Modifier.toggleable(
                            value = granted,
                            enabled = enabled,
                            role = Role.Switch,
                            onValueChange = { viewModel.setPermission(permission.name, it) },
                        ),
                        headlineContent = { Text(permission.name) },
                        trailingContent = {
                            Switch(
                                checked = granted,
                                enabled = enabled,
                                onCheckedChange = null,
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
            InnerBottomSpacer(innerPadding)
        }
    }
}
