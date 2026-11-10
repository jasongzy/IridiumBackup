package com.xayah.feature.main.processing.packages.backup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.model.StorageMode
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.CloudAccountPicker
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.ProcessingSetupScaffold
import com.xayah.feature.main.processing.R
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PagePackagesBackupProcessingSetup(localNavController: NavHostController, viewModel: BackupViewModelImpl) {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val request by viewModel.request.collectAsStateWithLifecycle()
    val packages = request?.packages.orEmpty()
    val packagesSize = remember(packages) { packages.sumOf { it.selectedDisplayStatsBytes }.toDouble().formatSize() }

    ProcessingSetupScaffold(
        scrollBehavior = scrollBehavior,
        snackbarHostState = viewModel.snackbarHostState,
        title = stringResource(id = R.string.setup),
        actions = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level24)
                    .paddingVertical(SizeTokens.Level8),
                horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level12, Alignment.End),
            ) {
                Button(
                    enabled = packages.isNotEmpty() && !isStarting &&
                        (uiState.storageType == StorageMode.Local || uiState.cloudEntity in accounts),
                    onClick = {
                        viewModel.emitIntentOnIO(FinishSetup(navController = localNavController))
                    }) {
                    Text(text = stringResource(id = R.string._continue))
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
        ) {
            val storageOptions = remember { listOf(context.getString(R.string.local), context.getString(R.string.cloud)) }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level16)
                    .paddingTop(SizeTokens.Level16)
            ) {
                storageOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        enabled = !isStarting,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = storageOptions.size),
                        onClick = {
                            viewModel.selectStorage(index)
                        },
                        selected = index == uiState.storageType.ordinal
                    ) {
                        Text(label)
                    }
                }
            }

            Title(title = stringResource(id = R.string.storage)) {
                AnimatedVisibility(uiState.storageType == StorageMode.Cloud) {
                    CloudAccountPicker(
                        accounts = accounts,
                        selected = uiState.cloudEntity,
                        autoSelectFirst = uiState.autoSelectFirstCloud,
                        onManage = { navController.navigateSingle(MainRoutes.Cloud.route) },
                        enabled = !isStarting,
                        onSelect = viewModel::selectCloudAccount,
                    )
                }

                val interactionSource = remember { MutableInteractionSource() }
                Clickable(
                    title = stringResource(id = R.string.apps),
                    value = if (request == null) stringResource(com.xayah.core.service.R.string.package_request_expired) else packagesSize,
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                    interactionSource = interactionSource,
                    content = {
                        PackageIcons(modifier = Modifier.paddingTop(SizeTokens.Level8), packages = packages)
                    }
                )
            }
            Title(title = stringResource(id = R.string.settings)) {
                Switchable(
                    key = KeyAutoScreenOff,
                    defValue = false,
                    title = stringResource(id = R.string.auto_screen_off),
                    checkedText = stringResource(id = R.string.auto_screen_off_desc),
                )
                Switchable(
                    enabled = request != null && !isStarting,
                    checked = request?.backupItself == true,
                    title = stringResource(id = R.string.backup_itself),
                    checkedText = stringResource(id = R.string.backup_itself_desc),
                    onCheckedChange = { checked -> request?.let { viewModel.setBackupOptions(it.copy(backupItself = !checked)) } },
                )
                Switchable(
                    enabled = request != null && !isStarting,
                    checked = request?.backupConfigs == true,
                    title = stringResource(id = R.string.backup_configs),
                    checkedText = stringResource(id = R.string.backup_configs_desc),
                    onCheckedChange = { checked -> request?.let { viewModel.setBackupOptions(it.copy(backupConfigs = !checked)) } },
                )
            }
        }
    }
}
