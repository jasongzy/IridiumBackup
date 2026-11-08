package com.xayah.core.data.repository

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.xayah.core.database.AppDatabase
import com.xayah.core.model.BlacklistAppItem
import com.xayah.core.model.BlacklistFileItem
import com.xayah.core.model.ConfigurationsBlacklist
import com.xayah.core.model.OpType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BlacklistImportTest {
    @Test
    fun failedReplacementKeepsBothLists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val old = ConfigurationsBlacklist(listOf(BlacklistAppItem("com.example.old", 10)), listOf(BlacklistFileItem("docs", "/sdcard/Documents")))
            val new = ConfigurationsBlacklist(listOf(BlacklistAppItem("com.example.new", 0)), listOf(BlacklistFileItem("pics", "/sdcard/Pictures")))
            db.importBlacklist(old)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON MediaEntity BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            check(runCatching { db.importBlacklist(new) }.isFailure)
            assertEquals(old.apps.single().packageName, db.packageDao().queryPackages(OpType.BACKUP, true).single().packageName)
            assertEquals(old.files.single().path, db.mediaDao().query(OpType.BACKUP, true).single().path)
            sql.execSQL("DROP TRIGGER fail_import")
            check(runCatching { db.importBlacklist(new.copy(apps = listOf(BlacklistAppItem("../outside", 0)))) }.isFailure)
            db.importBlacklist(new)
            assertEquals(new.apps.single().packageName, db.packageDao().queryPackages(OpType.BACKUP, true).single().packageName)
            assertEquals(new.files.single().path, db.mediaDao().query(OpType.BACKUP, true).single().path)
        } finally {
            db.close()
        }
    }
}
