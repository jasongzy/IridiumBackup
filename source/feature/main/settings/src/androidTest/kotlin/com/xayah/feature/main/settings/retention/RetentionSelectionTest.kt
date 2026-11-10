package com.xayah.feature.main.settings.retention

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.data.repository.AppBackupRepository
import com.xayah.core.data.repository.AppIconRepository
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.XposedModuleDetector
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.readBackupSavePath
import com.xayah.core.datastore.saveBackupSavePath
import com.xayah.core.model.AppKey
import com.xayah.core.model.BackupEngine
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.toBackupApp
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetentionSelectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val directory = TemporaryFolder(context.cacheDir)

    @Test
    fun cleanupDoesNotSelectBackupsTheUserUnchecked() = runBlocking {
        val previous = context.readBackupSavePath().first()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dir = directory.root.path
            context.saveBackupSavePath(dir)
            val root = RemoteRootService(context)
            val paths = PathUtil(context)
            val packages = PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), database.packageDao(), paths)
            val repository = AppBackupRepository(context, database, packages, AppIconRepository(context, root), XposedModuleDetector(context, root), root)
            val model = RetentionViewModel(context, repository)
            val key = AppKey("test.app", 0)
            val dao = database.appBackupDao()
            dao.insertMissingApp(key.toBackupApp())
            val revisions = (1L..3L).map {
                BackupRevisionEntity(key.packageName, 0, it, "1", 1, BackupEngine.LEGACY, ":$dir", "test.app/user_0@$it", 1)
            }
            dao.upsertRevisions(revisions)
            model.scan()
            val scanned = withTimeout(5_000) { model.uiState.first { it.hasScanned && !it.isScanning } }
            assertEquals(revisions.take(2).map { it.id }.toSet(), scanned.selectedIds)
            model.toggle(revisions[1].id)
            dao.updateRevisionNote(revisions.first().id, "protected")
            model.deleteSelected()
            val finished = withTimeout(5_000) { model.uiState.first { !it.isDeleting } }
            assertEquals(listOf(revisions[1].id), finished.candidates.filter { it.removable }.map { it.revision.id })
            assertEquals(emptySet<String>(), finished.selectedIds)
        } finally {
            context.saveBackupSavePath(previous)
            database.close()
        }
    }
}
