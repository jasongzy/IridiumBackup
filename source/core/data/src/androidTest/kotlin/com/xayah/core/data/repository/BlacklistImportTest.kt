package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.BlacklistFileItem
import com.xayah.core.model.CompressionType
import com.xayah.core.model.OpType
import com.xayah.core.model.PackageRestoreConfig
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.toPackageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BlacklistImportTest {
    @Test
    fun failedReplacementKeepsBothLists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            fun app(name: String, userId: Int) = PackageRestoreConfig(
                packageName = name, userId = userId, createdAt = 0, compressionType = CompressionType.TAR,
                label = name, versionName = "1", versionCode = 1, isSystemApp = false, hasKeystore = false,
                permissions = emptyList(), ssaid = "", dataStates = PackageDataStates(),
                dataStats = PackageDataStats(), displayStats = PackageDataStats(),
            ).toPackageEntity("", "").apply { indexInfo.opType = OpType.BACKUP }
            val old = app("com.example.old", 10)
            val new = app("com.example.new", 0)
            val docs = BlacklistFileItem("docs", "/sdcard/Documents")
            val pics = BlacklistFileItem("pics", "/sdcard/Pictures")
            db.importBlacklist(listOf(old), listOf(docs))
            db.importBlacklist(emptyList(), emptyList())
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON MediaEntity BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            check(runCatching { db.importBlacklist(listOf(new), listOf(pics)) }.isFailure)
            assertEquals(old.packageName, db.packageDao().queryPackages(OpType.BACKUP, true).single().packageName)
            assertEquals(docs.path, db.mediaDao().query(OpType.BACKUP, true).single().path)
            assertEquals(null, db.packageDao().query(new.packageName, OpType.BACKUP, new.userId))
            sql.execSQL("DROP TRIGGER fail_import")
            db.importBlacklist(listOf(new), listOf(pics))
            assertEquals(new.packageName, db.packageDao().queryPackages(OpType.BACKUP, true).single().packageName)
            assertEquals(pics.path, db.mediaDao().query(OpType.BACKUP, true).single().path)
        } finally {
            db.close()
        }
    }
}
