package com.xayah.core.ui.component

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.R
import com.xayah.core.ui.material3.AlertDialog
import com.xayah.core.ui.token.SizeTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class PathEntry(val path: String, val directory: Boolean)

@HiltViewModel
internal class LocalPathPickerViewModel @Inject constructor(private val rootService: RemoteRootService) : ViewModel() {
    suspend fun list(path: String, selectFile: Boolean): List<PathEntry>? = withTimeoutOrNull(15_000) {
        withContext(Dispatchers.IO) {
            try {
                val entries = rootService.readDirectory(path, includeFiles = selectFile)
                entries.directories.sorted().map { PathEntry(it, true) } + entries.files.sorted().map { PathEntry(it, false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun createFolder(parent: String, name: String): Boolean = withContext(Dispatchers.IO) {
        rootService.createDirectory(File(parent, name).path)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPathPicker(
    title: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    initialDirectory: String? = null,
    selectFile: Boolean = false,
    allowCreateDirectory: Boolean = false,
) {
    val viewModel = hiltViewModel<LocalPathPickerViewModel>()
    var history by rememberSaveable { mutableStateOf(listOf(initialDirectory ?: Environment.getExternalStorageDirectory().absolutePath)) }
    val directory = history.last()
    val listStates = rememberSaveableStateHolder()
    var refresh by remember { mutableStateOf(0) }
    Dialog(
        onDismissRequest = {
            if (history.size == 1) onDismiss() else {
                listStates.removeState(history.lastIndex)
                history = history.dropLast(1)
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val bars = rememberSystemUiController()
        val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect { bars.systemBarsDarkContentEnabled = lightBackground }
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(icon = Icons.Rounded.Close, tooltip = stringResource(R.string.cancel), onClick = onDismiss) },
            )
        }) { padding ->
            key(directory, refresh) {
                var entries by remember { mutableStateOf<List<PathEntry>?>(null) }
                var failed by remember { mutableStateOf(false) }
                var newFolder by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(selectFile) {
                    entries = viewModel.list(directory, selectFile)
                    failed = entries == null
                }
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(Modifier.padding(horizontal = SizeTokens.Level12), verticalAlignment = Alignment.CenterVertically) {
                        val parent = File(directory).parent
                        IconButton(icon = Icons.Rounded.ArrowUpward, tooltip = stringResource(R.string.parent_folder), enabled = parent != null) {
                            parent?.let { history += it }
                        }
                        BodyMediumText(
                            modifier = Modifier.weight(1f),
                            text = directory,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis,
                        )
                        if (allowCreateDirectory) {
                            IconButton(icon = Icons.Outlined.CreateNewFolder, tooltip = stringResource(R.string.new_folder), enabled = entries != null) {
                                newFolder = true
                            }
                        }
                    }
                    HorizontalDivider()
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        val listing = entries
                        when {
                            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BodyMediumText(text = stringResource(R.string.folder_read_failed), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.retry_folder)) }
                            }
                            listing == null -> CircularProgressIndicator()
                            listing.isEmpty() -> BodyMediumText(text = stringResource(R.string.folder_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> listStates.SaveableStateProvider(history.lastIndex) {
                                LazyColumn(Modifier.matchParentSize()) {
                                    items(listing, key = { it.path }) { item ->
                                        ListItem(
                                            modifier = Modifier.clickable { if (item.directory) history += item.path else onSelect(item.path) }
                                                .padding(horizontal = SizeTokens.Level8),
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                            headlineContent = { Text(File(item.path).name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                            leadingContent = {
                                                if (item.directory) Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                                else Icon(Icons.Outlined.Description, null)
                                            },
                                            trailingContent = if (item.directory) ({ Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }) else null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!selectFile) {
                        Button(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = SizeTokens.Level24, vertical = SizeTokens.Level8),
                            enabled = entries != null,
                            onClick = { onSelect(directory) },
                        ) { Text(stringResource(R.string.select_this_folder)) }
                    }
                }
                if (newFolder) {
                    NewFolderDialog(onDismiss = { newFolder = false }) { name ->
                        viewModel.createFolder(directory, name).also { if (it) history += File(directory, name).path }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: suspend (String) -> Boolean) {
    var input by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = input.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_folder)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = input,
                onValueChange = { input = it; failed = false },
                singleLine = true,
                enabled = !creating,
                isError = failed,
                supportingText = if (failed) ({ Text(stringResource(R.string.folder_create_failed)) }) else null,
                label = { Text(stringResource(R.string.folder_name)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !creating && name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name,
                onClick = {
                    creating = true
                    scope.launch { failed = !onCreate(name); creating = false }
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
