package com.xayah.feature.main.settings.verification

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.model.BackupVerificationStatus
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.LinearProgressIndicator
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.DateUtil
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.settings.R
import com.xayah.feature.main.settings.SettingsScaffold

@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageBackupVerification(
    viewModel: VerificationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current!!
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var showCleanupConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectedResult by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(R.string.verification_results),
        actions = {
            if (uiState.failedBackups.isNotEmpty()) {
                IconButton(
                    icon = Icons.Outlined.DeleteSweep,
                    tooltip = stringResource(R.string.clean_failed_backups),
                    enabled = !uiState.isRunning && !uiState.isCleaning,
                    onClick = { showCleanupConfirmation = true },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    VerificationSummary(uiState)
                }

                if (uiState.isComplete && uiState.results.isEmpty()) {
                    item {
                        EmptyMessage(stringResource(R.string.no_backups_to_verify))
                    }
                } else if (uiState.isComplete && uiState.failedBackups.isEmpty()) {
                    item {
                        EmptyMessage(stringResource(R.string.all_backups_verified))
                    }
                }

                items(uiState.results, key = { it.revision.id }) { result ->
                    VerificationItem(result = result, onClick = { selectedResult = result.revision.id })
                    HorizontalDivider()
                }
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SizeTokens.Level16),
                enabled = uiState.total > 0 && !uiState.isRunning && !uiState.isCleaning,
                onClick = viewModel::verify,
            ) {
                Text(stringResource(if (uiState.isComplete) R.string.verify_again else R.string.verify_all_backups))
            }
        }
    }

    if (showCleanupConfirmation) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirmation = false },
            title = { Text(stringResource(R.string.clean_failed_backups)) },
            text = { Text(stringResource(R.string.confirm_clean_failed_backups, uiState.failedBackups.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanupConfirmation = false
                        viewModel.cleanFailedBackups()
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    uiState.results.firstOrNull { it.revision.id == selectedResult }?.let { result ->
        val revision = result.revision
        AlertDialog(
            onDismissRequest = { selectedResult = null },
            title = { Text(result.appLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8)) {
                    Text(revision.packageName)
                    Text(
                        stringResource(
                            R.string.verification_backup_details,
                            revision.appVersionName,
                            revision.appVersionCode,
                            DateUtil.formatTimestamp(revision.createdAt, DateUtil.PATTERN_YMD_HMS),
                            revision.sizeBytes.toDouble().formatSize(),
                        )
                    )
                    Text(
                        text = stringResource(result.status.stringRes()),
                        color = if (result.status == BackupVerificationStatus.VALID) {
                            ThemedColorSchemeKeyTokens.GreenPrimary.value
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    result.issues.forEach { issue ->
                        Text(
                            text = issue.description(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedResult = null
                        navController.navigateSingle(
                            MainRoutes.AppRevisions.getRoute(revision.packageName, revision.userId)
                        )
                    },
                ) {
                    Text(stringResource(R.string.view_app_backups))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedResult = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@Composable
private fun VerificationSummary(uiState: VerificationUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SizeTokens.Level16),
        verticalArrangement = Arrangement.spacedBy(SizeTokens.Level8),
    ) {
        when {
            uiState.isCleaning -> {
                LinearProgressIndicator(
                    progress = if (uiState.cleanupTotal == 0) 0f
                    else uiState.cleanupCompleted.toFloat() / uiState.cleanupTotal,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.cleanup_progress, uiState.cleanupCompleted, uiState.cleanupTotal))
            }

            uiState.isRunning -> {
                LinearProgressIndicator(
                    progress = if (uiState.total == 0) 0f else uiState.completed.toFloat() / uiState.total,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.verification_progress, uiState.completed, uiState.total))
            }

            uiState.isComplete -> {
                LinearProgressIndicator(progress = 1f, modifier = Modifier.fillMaxWidth())
                Text(
                    text = if (uiState.failedBackups.isEmpty()) {
                        stringResource(R.string.verification_valid_count, uiState.validCount)
                    } else {
                        stringResource(R.string.verification_summary, uiState.validCount, uiState.failedBackups.size)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.cleanupFailedCount > 0) {
                    Text(
                        text = stringResource(R.string.cleanup_failed, uiState.cleanupFailedCount),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.backups_ready_for_verification, uiState.total),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SizeTokens.Level32),
        contentAlignment = Alignment.Center,
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VerificationItem(
    result: AppBackupRepository.VerificationResult,
    onClick: () -> Unit,
) {
    val valid = result.status == BackupVerificationStatus.VALID
    val revision = result.revision
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (valid) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer,
        ),
        leadingContent = {
            PackageIconImage(
                packageName = revision.packageName,
                size = SizeTokens.Level48,
                refreshKey = result,
            )
        },
        headlineContent = { Text(result.appLabel) },
        supportingContent = {
            Column {
                Text(revision.packageName)
                Text(
                    stringResource(
                        R.string.verification_item_details,
                        revision.appVersionName,
                        revision.appVersionCode,
                        DateUtil.formatTimestamp(revision.createdAt, DateUtil.PATTERN_YMD_HMS),
                    )
                )
                Text(
                    text = stringResource(result.status.stringRes()),
                    color = if (valid) ThemedColorSchemeKeyTokens.GreenPrimary.value else MaterialTheme.colorScheme.error,
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = if (valid) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(result.status.stringRes()),
                tint = if (valid) ThemedColorSchemeKeyTokens.GreenPrimary.value else MaterialTheme.colorScheme.error,
            )
        },
    )
}

private fun BackupVerificationStatus.stringRes() = when (this) {
    BackupVerificationStatus.NOT_VERIFIED -> R.string.not_verified
    BackupVerificationStatus.VALID -> R.string.backup_valid
    BackupVerificationStatus.DAMAGED -> R.string.backup_damaged
}

@Composable
private fun AppBackupRepository.VerificationIssue.description(): String {
    val message = stringResource(
        when (type) {
            AppBackupRepository.VerificationIssueType.MANIFEST_MISSING -> R.string.verification_manifest_missing
            AppBackupRepository.VerificationIssueType.MANIFEST_INVALID -> R.string.verification_manifest_invalid
            AppBackupRepository.VerificationIssueType.METADATA_MISMATCH -> R.string.verification_metadata_mismatch
            AppBackupRepository.VerificationIssueType.FILE_MISSING -> R.string.verification_file_missing
            AppBackupRepository.VerificationIssueType.FILE_SIZE_MISMATCH -> R.string.verification_file_size_mismatch
            AppBackupRepository.VerificationIssueType.FILE_CHECKSUM_MISMATCH -> R.string.verification_file_checksum_mismatch
            AppBackupRepository.VerificationIssueType.NOT_LOCAL -> R.string.verification_not_local
        }
    )
    return fileName?.let { "$it: $message" } ?: message
}
