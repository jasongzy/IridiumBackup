package com.xayah.feature.main.processing.packages.restore

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.xayah.core.model.util.formatSize
import com.xayah.core.datastore.KeyAutoScreenOff
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Switchable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.paddingVertical
import com.xayah.core.ui.component.select
import com.xayah.core.ui.token.SizeTokens
import com.xayah.feature.main.processing.GetUsers
import com.xayah.feature.main.processing.ProcessingSetupScaffold
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PagePackagesRestoreProcessingSetup(localNavController: NavHostController, viewModel: RestoreViewModelImpl) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val packages = preview?.packages.orEmpty()
    val restoreUsers by viewModel.restoreUsers.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()
    val isCheckingRestore by viewModel.isCheckingRestore.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var restoreUser by rememberSaveable { mutableIntStateOf(-1) }
    val currentIndex = restoreUsers.indexOfFirst { it.enum == restoreUser }
    val targetConflict = remember(preview, restoreUser) { preview?.copy(userId = restoreUser)?.conflictingTarget() }

    LaunchedEffect(null) {
        viewModel.launchOnIO {
            viewModel.emitIntent(SetCloudEntity(""))
            viewModel.emitIntent(GetUsers)
            viewModel.emitIntent(UpdateApps)
        }
    }

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
                    enabled = packages.isNotEmpty() && currentIndex >= 0 && targetConflict == null && !isCheckingRestore && !isStarting,
                    onClick = {
                        viewModel.continueRestore(restoreUser, localNavController)
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
            Title(title = stringResource(id = R.string.storage)) {
                val interactionSource = remember { MutableInteractionSource() }
                Clickable(
                    title = stringResource(id = R.string.apps),
                    value = preview?.sizeBytes?.toDouble()?.formatSize() ?: stringResource(R.string.loading),
                    leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                    interactionSource = interactionSource,
                    content = {
                        PackageIcons(modifier = Modifier.paddingTop(SizeTokens.Level8), packages = packages)
                    }
                )
            }
            Title(title = stringResource(id = R.string.settings)) {
                val dialogState = LocalSlotScope.current!!.dialogSlot
                Selectable(
                    enabled = restoreUsers.isNotEmpty() && (restoreUsers.size > 1 || currentIndex < 0) && !isCheckingRestore && !isStarting,
                    title = stringResource(id = R.string.restore_user),
                    value = stringResource(id = R.string.restore_user_desc),
                    current = restoreUsers.getOrNull(currentIndex)?.title ?: stringResource(R.string.not_selected),
                ) {
                    viewModel.launchOnIO {
                        val (state, selectedIndex) = dialogState.select(
                            title = context.getString(R.string.restore_user),
                            defIndex = currentIndex.coerceAtLeast(0),
                            items = restoreUsers
                        )
                        if (state.isConfirm) {
                            restoreUser = restoreUsers[selectedIndex].enum ?: -1
                        }
                    }
                }
                if (targetConflict != null || currentIndex < 0 && preview != null) {
                    Text(
                        text = when {
                            targetConflict != null -> stringResource(com.xayah.core.service.R.string.restore_target_conflict, targetConflict.packageName, targetConflict.userId)
                            restoreUsers.isEmpty() -> stringResource(com.xayah.core.service.R.string.restore_users_unavailable)
                            restoreUser == -1 -> stringResource(R.string.select_existing_restore_user)
                            else -> stringResource(com.xayah.core.service.R.string.restore_user_missing, restoreUser)
                        },
                        modifier = Modifier.paddingHorizontal(SizeTokens.Level24),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Switchable(
                    key = KeyAutoScreenOff,
                    defValue = false,
                    title = stringResource(id = R.string.auto_screen_off),
                    checkedText = stringResource(id = R.string.auto_screen_off_desc),
                )
            }
        }
    }

    warnings?.let { risks ->
        val isBatch = packages.size > 1
        AlertDialog(
            onDismissRequest = viewModel::dismissWarnings,
            title = { Text(stringResource(when {
                risks.damaged.isEmpty() -> R.string.possible_keystore_data
                risks.keystore.isEmpty() -> R.string.backup_damaged
                else -> R.string.restore
            })) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (risks.damaged.isNotEmpty()) {
                        Text(stringResource(R.string.backup_damaged_restore_warning))
                        risks.damaged.forEach { Text("• ${it.packageInfo.label} (${it.packageName}, ${it.userId})") }
                    }
                    if (risks.keystore.isNotEmpty()) {
                        if (risks.damaged.isNotEmpty()) Spacer(Modifier.height(SizeTokens.Level12))
                        Text(stringResource(if (isBatch) R.string.keystore_restore_warning_batch else R.string.keystore_restore_warning))
                        if (isBatch) risks.keystore.forEach { Text("• ${it.packageInfo.label} (${it.packageName}, ${it.userId})") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWarnings(localNavController) }) {
                    Text(stringResource(R.string.restore_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissWarnings) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
