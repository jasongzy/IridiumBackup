package com.xayah.feature.main.settings

import android.content.Context
import android.widget.Toast
import com.xayah.core.util.FileUtil
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.DirectoryRepository
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.work.WorkManagerInitializer
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data object IndexUiState : UiState

sealed class IndexUiIntent : UiIntent {
    data object ClearCache : IndexUiIntent()
    data object LoadSystemApps : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    directoryRepo: DirectoryRepository,
    private val rootService: RemoteRootService,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            IndexUiIntent.LoadSystemApps -> WorkManagerInitializer.reloadSystemApps(context)
            IndexUiIntent.ClearCache -> {
                val directories = buildList {
                    add(context.cacheDir)
                    addAll(context.externalCacheDirs.filterNotNull())
                }.distinctBy { it.absolutePath }
                suspend fun cacheSize() = directories.sumOf { directory ->
                    rootService.calculateSize(directory.path).takeIf { it > 0 } ?: FileUtil.calculateSize(directory.path)
                }
                val before = cacheSize()
                val deleted = directories.flatMap { it.listFiles()?.toList().orEmpty() }
                    .map { FileUtil.deleteRecursively(it.path) || rootService.deleteRecursively(it.path) }.all { it }
                val after = cacheSize()
                val removed = (before - after).coerceAtLeast(0)
                val message = when {
                    !deleted -> context.getString(R.string.cache_clear_failed)
                    removed > 0 -> context.getString(R.string.cache_cleared_amount, removed.toDouble().formatSize())
                    else -> context.getString(R.string.cache_already_clear)
                }
                withMainContext {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val _directory: Flow<DirectoryEntity?> = directoryRepo.querySelectedByDirectoryTypeFlow().flowOnIO()
    val directoryState: StateFlow<DirectoryEntity?> = _directory.stateInScope(null)
}
