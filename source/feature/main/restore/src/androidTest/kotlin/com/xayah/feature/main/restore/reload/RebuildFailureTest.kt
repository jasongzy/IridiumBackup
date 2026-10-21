package com.xayah.feature.main.restore.reload

import android.os.Binder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.AppIconRepository
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.XposedModuleDetector
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.toBackupApp
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.parcelables.DirectoryListingParcelable
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
class RebuildFailureTest {
    @Test
    fun unreadableDirectoryPreservesTheIndexAndAllowsRetry() = runBlocking {
        withTimeout(5_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            val store = ViewModelStore()
            try {
                val readable = AtomicBoolean(false)
                val binder = Binder()
                val remote = Proxy.newProxyInstance(
                    IRemoteRootService::class.java.classLoader,
                    arrayOf(IRemoteRootService::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "asBinder" -> binder
                        "listFilePathsChecked" -> DirectoryListingParcelable(readable.get(), emptyList())
                        else -> error("Unexpected root operation: ${method.name}")
                    }
                }
                val root = RemoteRootService(context).apply {
                    javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
                }
                val paths = PathUtil(context)
                val clouds = CloudRepository(context, root, database.cloudDao())
                val packages = PackageRepository(context, root, clouds, database.packageDao(), paths)
                val repository = AppBackupRepository(
                    context, database.appBackupDao(), packages, AppIconRepository(context, root, paths),
                    XposedModuleDetector(context, root), root, paths,
                )
                val app = AppKey("com.example.backup", 0).toBackupApp()
                val revision = BackupRevisionEntity(
                    app.packageName, 0, 1234, "1.0", 1, BackupEngine.LEGACY,
                    ":${context.localBackupSaveDir()}", "com.example.backup/user_0@1234", 1,
                )
                database.appBackupDao().upsertApps(listOf(app))
                database.appBackupDao().upsertRevision(revision)
                val model = IndexViewModel(context, repository)
                withContext(Dispatchers.Main) {
                    store.put("rebuild", model)
                    model.emitIntent(IndexUiIntent.Rebuild)
                }
                assertFalse(model.uiState.value.isLoading)
                assertTrue(model.uiState.value.text.contains("Unable to read directory"))
                assertEquals(listOf(revision), database.appBackupDao().getRevisions(revision.repositoryId))

                readable.set(true)
                withContext(Dispatchers.Main) { model.emitIntent(IndexUiIntent.Rebuild) }
                assertFalse(model.uiState.value.isLoading)
                assertEquals(context.getString(com.xayah.feature.main.restore.R.string.rebuild_finished, 0), model.uiState.value.text)
                assertTrue(database.appBackupDao().getRevisions(revision.repositoryId).isEmpty())
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
                database.close()
            }
        }
    }
}
