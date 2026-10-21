package com.xayah.feature.main.list

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.OpType
import com.xayah.core.model.AppKey
import com.xayah.core.model.Target
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.ui.R
import com.xayah.core.ui.component.BodyMediumText
import com.xayah.core.ui.component.IconButton
import com.xayah.core.ui.component.PackageIconImage
import com.xayah.core.ui.component.Surface
import com.xayah.core.ui.component.TitleMediumText
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.theme.ThemedColorSchemeKeyTokens
import com.xayah.core.ui.theme.value
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.util.navigateSingle

@Composable
fun ListItems(
    viewModel: ListItemsViewModel = hiltViewModel(),
    scrollState: LazyListState,
    selectionMode: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = scrollState,
    ) {
        item(key = "-1") {
            Spacer(modifier = Modifier.size(SizeTokens.Level1))
        }

        listItems(uiState = uiState, viewModel = viewModel, selectionMode = selectionMode)

        item(key = "-2") {
            Spacer(modifier = Modifier.size(SizeTokens.Level128))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.listItems(
    uiState: ListItemsUiState,
    viewModel: ListItemsViewModel,
    selectionMode: Boolean,
) {
    when (uiState) {
        is ListItemsUiState.Success.Apps -> {
            items(
                items = uiState.appList,
                key = { "${it.app.packageName}-${it.app.userId}" },
                contentType = { "app" },
            ) { item ->
                val navController = LocalNavController.current!!
                AppItem(
                    item = item,
                    selectionMode = selectionMode,
                    showInstallTime = uiState.showInstallTime,
                    showDataSize = uiState.showDataSize,
                    showUpdateTime = uiState.showUpdateTime,
                    onClick = {
                        if (selectionMode) {
                            viewModel.onAppSelectedChanged(item.app.key, !item.app.selected)
                        } else {
                            navController.navigateSingle(
                                MainRoutes.AppRevisions.getRoute(item.app.packageName, item.app.userId)
                            )
                        }
                    },
                    onLongClick = { viewModel.enterSelection(item.app.key) },
                    onSelectedChanged = viewModel::onAppSelectedChanged,
                )
            }
        }

        is ListItemsUiState.Success.Files -> {
            items(items = uiState.fileList, key = { it.id }, contentType = { "file" }) { item ->
                val navController = LocalNavController.current!!
                FileItem(
                    id = item.id,
                    name = item.name,
                    path = item.path,
                    preserveId = item.preserveId,
                    selected = item.selected,
                    onClick = {
                        navController.navigateSingle(MainRoutes.Details.getRoute(Target.Files, uiState.opType, item.id))
                    },
                    onSelectedChanged = viewModel::onFileSelectedChanged
                )
            }
        }

        else -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppItem(
    item: AppListItem,
    selectionMode: Boolean,
    showInstallTime: Boolean,
    showDataSize: Boolean,
    showUpdateTime: Boolean,
    onSelectedChanged: (AppKey, Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val app = item.app
    val title = if (app.versionName.isEmpty()) app.label else "${app.label} · ${app.versionName}"
    val backupSummary = item.latestRevisionAt?.takeIf { item.revisionCount > 0 }?.let { timestamp ->
        stringResource(
            com.xayah.feature.main.list.R.string.backup_summary,
            item.revisionCount,
            DateUtils.getRelativeTimeSpanString(timestamp),
        )
    } ?: stringResource(com.xayah.feature.main.list.R.string.no_backups)
    val metadata = if (!app.isInstalled) stringResource(com.xayah.feature.main.list.R.string.not_installed) else null
    val systemColor = if (app.isSystemApp) ThemedColorSchemeKeyTokens.Error.value else Color.Unspecified
    val formattedUpdateTime = remember(app.lastUpdateTime) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(app.lastUpdateTime))
    }
    val formattedInstallTime = remember(app.firstInstallTime) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(app.firstInstallTime))
    }
    val installTime = if (showInstallTime && app.firstInstallTime > 0) {
        stringResource(
            com.xayah.feature.main.list.R.string.installed_at,
            formattedInstallTime,
        )
    } else null
    val updateTime = if (showUpdateTime && app.lastUpdateTime > 0) {
        stringResource(
            com.xayah.feature.main.list.R.string.updated_at,
            formattedUpdateTime,
        )
    } else null
    val dataSize = if (showDataSize && app.isInstalled) {
        stringResource(
            com.xayah.feature.main.list.R.string.app_data_size,
            app.dataSizeBytes.toDouble().formatSize(),
        )
    } else null

    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = SizeTokens.Level16, vertical = SizeTokens.Level12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            PackageIconImage(packageName = app.packageName, size = 36.dp)

            Column(modifier = Modifier.weight(1f)) {
                TitleMediumText(text = title.ifEmpty { app.packageName }, color = systemColor, maxLines = 1)
                BodyMediumText(
                    text = backupSummary,
                    color = if (item.revisionCount > 0) {
                        ThemedColorSchemeKeyTokens.GreenPrimary.value
                    } else {
                        ThemedColorSchemeKeyTokens.Outline.value
                    },
                    maxLines = 1,
                )
                if (app.isFrozen || app.isUpdatedSystemApp || app.isXposedModule || item.labels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = SizeTokens.Level2),
                        horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level4),
                    ) {
                        if (app.isFrozen) {
                            androidx.compose.material3.Surface(
                                color = ThemedColorSchemeKeyTokens.BluePrimaryContainer.value,
                                contentColor = ThemedColorSchemeKeyTokens.BlueOnPrimaryContainer.value,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = SizeTokens.Level6, vertical = SizeTokens.Level1),
                                    text = stringResource(com.xayah.feature.main.list.R.string.frozen),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (app.isUpdatedSystemApp) {
                            androidx.compose.material3.Surface(
                                color = ThemedColorSchemeKeyTokens.ErrorContainer.value,
                                contentColor = ThemedColorSchemeKeyTokens.OnErrorContainer.value,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = SizeTokens.Level6, vertical = SizeTokens.Level1),
                                    text = stringResource(com.xayah.feature.main.list.R.string.updated),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (app.isXposedModule) {
                            androidx.compose.material3.Surface(
                                color = XposedPink,
                                contentColor = XposedOnPink,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    modifier = Modifier.padding(horizontal = SizeTokens.Level6, vertical = SizeTokens.Level1),
                                    text = stringResource(com.xayah.feature.main.list.R.string.xposed),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        val labelLimit = (
                            3 - listOf(app.isFrozen, app.isUpdatedSystemApp, app.isXposedModule).count { it }
                        ).coerceAtLeast(0)
                        item.labels.take(labelLimit).forEach { label ->
                            val color = Color(label.colorArgb)
                            Text(
                                modifier = Modifier
                                    .border(1.dp, color, MaterialTheme.shapes.small)
                                    .padding(horizontal = SizeTokens.Level6, vertical = SizeTokens.Level1),
                                text = label.label,
                                color = color,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        if (item.labels.size > labelLimit) {
                            Text("+${item.labels.size - labelLimit}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (metadata != null) {
                    BodyMediumText(
                        text = metadata,
                        color = ThemedColorSchemeKeyTokens.Error.value,
                        maxLines = 1,
                    )
                }
                if (updateTime != null) {
                    BodyMediumText(text = updateTime, color = ThemedColorSchemeKeyTokens.Outline.value, maxLines = 1)
                }
                if (installTime != null) {
                    BodyMediumText(text = installTime, color = ThemedColorSchemeKeyTokens.Outline.value, maxLines = 1)
                }
                if (dataSize != null) {
                    BodyMediumText(text = dataSize, color = ThemedColorSchemeKeyTokens.Outline.value, maxLines = 1)
                }
            }

            if (app.isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (app.preserveId != 0L) {
                        Icon(imageVector = Icons.Outlined.Shield, contentDescription = null)
                    }
                }
            }
            if (selectionMode) {
                VerticalDivider(modifier = Modifier.height(SizeTokens.Level32))
                Checkbox(
                    checked = app.selected,
                    onCheckedChange = { onSelectedChanged(app.key, !app.selected) },
                )
            } else if (!selectionMode) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }
    }
}

private val XposedPink = Color(0xFFF48FB1)
private val XposedOnPink = Color(0xFF3E001D)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    id: Long,
    name: String,
    path: String,
    preserveId: Long,
    selected: Boolean,
    onSelectedChanged: (Long, Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(SizeTokens.Level16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SizeTokens.Level16)
        ) {
            PackageIconImage(icon = Icons.Rounded.Folder, packageName = "", inCircleShape = true, size = SizeTokens.Level32)

            Column(modifier = Modifier.weight(1f)) {
                TitleMediumText(text = name.ifEmpty { stringResource(id = R.string.unknown) }, maxLines = 1)
                BodyMediumText(text = path, color = ThemedColorSchemeKeyTokens.Outline.value, maxLines = 1)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (preserveId != 0L) {
                    Icon(imageVector = Icons.Outlined.Shield, contentDescription = null)
                }
            }
            VerticalDivider(
                modifier = Modifier.height(SizeTokens.Level32)
            )
            Checkbox(
                checked = selected,
                onCheckedChange = { onSelectedChanged(id, selected.not()) }
            )
        }
    }
}

@Composable
fun AnimatedDataIndicator(
    flag: Int,
    onClick: (Int) -> Unit,
) {
    IconButton(
        icon = when (flag) {
            PackageEntity.FLAG_NONE -> ImageVector.vectorResource(id = R.drawable.ic_rounded_cancel_circle)
            PackageEntity.FLAG_APK -> ImageVector.vectorResource(id = R.drawable.ic_rounded_android_circle)
            PackageEntity.FLAG_DATA -> ImageVector.vectorResource(id = R.drawable.ic_rounded_database_circle)
            PackageEntity.FLAG_ALL -> ImageVector.vectorResource(id = R.drawable.ic_rounded_check_circle)
            else -> ImageVector.vectorResource(id = R.drawable.ic_rounded_package_2_circle)
        },
        tint = when (flag) {
            PackageEntity.FLAG_NONE -> ThemedColorSchemeKeyTokens.Error.value
            PackageEntity.FLAG_ALL -> ThemedColorSchemeKeyTokens.GreenPrimary.value
            else -> ThemedColorSchemeKeyTokens.YellowPrimary.value
        },
        tooltip = stringResource(
            when (flag) {
                PackageEntity.FLAG_NONE -> R.string.no_item_selected
                PackageEntity.FLAG_APK -> R.string.apk_selected
                PackageEntity.FLAG_DATA -> R.string.data_selected
                PackageEntity.FLAG_ALL -> R.string.all_selected
                else -> R.string.custom_selected
            }
        ),
    ) {
        onClick(flag)
    }
}
