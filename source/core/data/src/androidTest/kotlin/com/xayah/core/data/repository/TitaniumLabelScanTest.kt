package com.xayah.core.data.repository

import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.datastore.readLabelColors
import com.xayah.core.datastore.saveLabelColors
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.rootservice.IRemoteRootService
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.util.PathUtil
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitaniumLabelScanTest {
    @Test
    fun importedStatusMatchesTheDestinationAppIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousColors = context.readLabelColors().first()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = File.createTempFile("titanium-source-", ".db", context.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.execSQL("CREATE TABLE labels (_id INTEGER, labelName TEXT, labelColor INTEGER)")
                db.execSQL("CREATE TABLE packages (id_label INTEGER, packageName TEXT)")
                db.execSQL("INSERT INTO labels VALUES (1, 'tag', 255)")
                db.execSQL("INSERT INTO packages VALUES (1, 'com.example.secondary'), (1, 'com.example.revision')")
            }
            val binder = Binder()
            val remote = Proxy.newProxyInstance(
                IRemoteRootService::class.java.classLoader, arrayOf(IRemoteRootService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "asBinder" -> binder
                    "copyTo" -> {
                        File(args!![0] as String).copyTo(File(args[1] as String), overwrite = args[2] as Boolean)
                        true
                    }
                    else -> error("Unexpected root operation: ${method.name}")
                }
            }
            val root = RemoteRootService(context).apply {
                javaClass.getDeclaredField("mService").apply { isAccessible = true }.set(this, remote)
            }
            val paths = PathUtil(context)
            val packages = PackageRepository(context, root, CloudRepository(context, root, database.cloudDao()), database.packageDao(), paths)
            val icons = AppIconRepository(context, root, paths)
            val modules = XposedModuleDetector(context, root)
            val backups = AppBackupRepository(context, database.appBackupDao(), packages, icons, modules, root, paths)
            val labels = LabelsRepo(context, Dispatchers.Default, database.labelDao())
            val repository = TitaniumImportRepository(context, root, paths, backups, icons, modules, labels)
            val otherRefs = listOf(
                LabelAppCrossRefEntity("tag", "com.example.secondary", 10, 0),
                LabelAppCrossRefEntity("tag", "com.example.revision", 0, 1234),
            )
            labels.addLabels(listOf(LabelEntity("tag")))
            labels.setLabelColor("tag", 0xFF0000FFL)
            labels.addLabelAppCrossRefs(otherRefs)

            val candidates = repository.scanLabels(source.path)
            assertFalse(candidates.single().imported)
            assertEquals(otherRefs.toSet(), labels.getAppRefs().toSet())

            val results = repository.importLabels(candidates) { _, _, _ -> }
            assertEquals(TitaniumImportRepository.Status.IMPORTED, results.single().status)
            assertEquals(
                (otherRefs + otherRefs.map { it.copy(userId = 0, preserveId = 0) }).toSet(),
                labels.getAppRefs().toSet(),
            )
            assertTrue(repository.scanLabels(source.path).single().imported)
        } finally {
            context.saveLabelColors(previousColors)
            database.close()
            SQLiteDatabase.deleteDatabase(source)
        }
    }
}
