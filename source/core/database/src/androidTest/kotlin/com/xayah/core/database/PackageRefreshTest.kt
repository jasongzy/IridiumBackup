package com.xayah.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DataState
import com.xayah.core.model.OpType
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageDisplayStatsEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.PackageUpdateEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageRefreshTest {
    private fun app() = PackageEntity(
        id = 1,
        indexInfo = PackageIndexInfo(OpType.BACKUP, "com.example.app", 0, CompressionType.TAR, 0, "", ""),
        packageInfo = PackageInfo("Example", "1.0", 1, 0, 10, 20),
        extraInfo = PackageExtraInfo(10001, false, emptyList(), "", 0, false, false, false, true),
        dataStates = PackageDataStates(),
        storageStats = PackageStorageStats(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(),
    )

    @Test
    fun blacklistChangesApplyAcrossTheBindLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.packageDao()
            val first = app()
            val second = first.copy(id = 2, indexInfo = first.indexInfo.copy(packageName = "second.app"))
            dao.upsert(listOf(first, second))
            val ids = listOf(first.id) + (1..SQLITE_BIND_LIMIT).map { -it.toLong() } + second.id
            dao.setBlocked(ids, true)
            assertEquals(setOf(first.id, second.id), dao.queryPackages(OpType.BACKUP, true).map { it.id }.toSet())
            dao.setBlocked(listOf(first.id), false)
            assertEquals(listOf(second.id), dao.queryPackages(OpType.BACKUP, true).map { it.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun refreshPreservesCurrentChoicesAndDoesNotReinsertRemovedApps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.packageDao()
            val app = app()
            dao.upsert(app)
            val refresh = PackageUpdateEntity(
                id = app.id,
                packageInfo = app.packageInfo.copy(versionName = "2.0", versionCode = 2),
                uid = 10002,
                hasKeystore = true,
                permissions = emptyList(),
                ssaid = "updated",
                firstUpdated = true,
                enabled = false,
            )
            dao.setBlocked(listOf(app.id), true)
            val displayStats = PackageDataStats(apkBytes = 123)
            dao.updateDisplayStats(PackageDisplayStatsEntity(app.id, displayStats))
            val selections = DataState.NotSelected.name
            dao.selectDataItemsById(app.id, DataState.Selected.name, selections, selections, selections, selections, selections)

            val current = dao.queryById(app.id)!!
            val expected = current.copy(
                packageInfo = refresh.packageInfo,
                extraInfo = current.extraInfo.copy(uid = 10002, hasKeystore = true, ssaid = "updated", firstUpdated = true, enabled = false),
            )
            dao.update(refresh)
            assertEquals(expected, dao.queryById(app.id))
            dao.update(listOf(refresh))
            assertEquals(expected, dao.queryById(app.id))

            dao.deleteByPkgNames(OpType.BACKUP, app.userId, (1..1_000).mapTo(mutableSetOf()) { "absent.$it" } + app.packageName)
            dao.update(refresh)
            dao.update(listOf(refresh))
            assertNull(dao.queryById(app.id))
        } finally {
            database.close()
        }
    }
}
