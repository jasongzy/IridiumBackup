package com.xayah.feature.main.settings.blacklist

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.database.AppDatabase
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class BlacklistSelectionTest {
    @Test
    fun selectionReflectsCurrentItemsAndSurvivesPendingRemoval() = runBlocking {
        withTimeout(5_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val store = ViewModelStore()
            try {
                val dao = database.packageDao()
                dao.upsert((1L..3L).map { id ->
                    PackageEntity(
                        id = id,
                        indexInfo = PackageIndexInfo(OpType.BACKUP, "com.example.app$id", 0, CompressionType.TAR, 0, "", ""),
                        packageInfo = PackageInfo("App $id", "1.0", 1, 0, 0, 0),
                        extraInfo = PackageExtraInfo(10000 + id.toInt(), false, emptyList(), "", 0, true, false, false, true),
                        dataStates = PackageDataStates(), storageStats = PackageStorageStats(),
                        dataStats = PackageDataStats(), displayStats = PackageDataStats(),
                    )
                })
                val removalStarted = CompletableDeferred<Unit>()
                val continueRemoval = CompletableDeferred<Unit>()
                val delayed = object : PackageDao by dao {
                    override suspend fun setBlocked(id: Long, blocked: Boolean) {
                        removalStarted.complete(Unit)
                        continueRemoval.await()
                        dao.setBlocked(id, blocked)
                    }
                }
                val root = RemoteRootService(context)
                val paths = PathUtil(context)
                val cloud = CloudRepository(context, root, database.cloudDao())
                val model = IndexViewModel(
                    PackageRepository(context, root, cloud, delayed, paths),
                    MediaRepository(context, root, cloud, database.mediaDao(), paths),
                )
                withContext(Dispatchers.Main) { store.put("blacklist", model) }
                model.packagesState.first { it.size == 3 }
                model.emitIntent(IndexUiIntent.SelectAll)
                model.emitIntent(IndexUiIntent.SelectApp(1))
                model.emitIntent(IndexUiIntent.SelectAll)
                assertEquals(setOf(1L, 2L, 3L), model.uiState.value.appIds)

                model.emitIntent(IndexUiIntent.SelectAll)
                model.emitIntent(IndexUiIntent.SelectApp(1))
                val removal = launch { model.emitIntent(IndexUiIntent.RemoveSelected) }
                removalStarted.await()
                model.emitIntent(IndexUiIntent.SelectApp(2))
                continueRemoval.complete(Unit)
                removal.join()
                assertEquals(setOf(2L), model.uiState.value.appIds)
                assertEquals(setOf(2L, 3L), dao.queryPackages(OpType.BACKUP, blocked = true).map { it.id }.toSet())
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { store.clear() }
                database.close()
            }
        }
    }
}
