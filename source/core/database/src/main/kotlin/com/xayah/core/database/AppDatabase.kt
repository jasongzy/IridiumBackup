package com.xayah.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.xayah.core.database.dao.CloudDao
import com.xayah.core.database.dao.AppBackupDao
import com.xayah.core.database.dao.DirectoryDao
import com.xayah.core.database.dao.LabelDao
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.database.util.StringListConverters
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.BackupAppEntity
import com.xayah.core.model.BackupRevisionEntity
import com.xayah.core.model.database.DirectoryEntity
import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.database.LabelFileCrossRefEntity
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.ProcessingInfoEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity

const val SQLITE_BIND_LIMIT = 999

@Database(
    version = 10,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 9, to = 10)],
    entities = [
        PackageEntity::class,
        MediaEntity::class,
        DirectoryEntity::class,
        CloudEntity::class,
        TaskEntity::class,
        TaskDetailPackageEntity::class,
        TaskDetailMediaEntity::class,
        ProcessingInfoEntity::class,
        LabelEntity::class,
        LabelAppCrossRefEntity::class,
        LabelFileCrossRefEntity::class,
        BackupAppEntity::class,
        BackupRevisionEntity::class,
    ],
)
@TypeConverters(StringListConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun packageDao(): PackageDao
    abstract fun mediaDao(): MediaDao
    abstract fun taskDao(): TaskDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun cloudDao(): CloudDao
    abstract fun labelDao(): LabelDao
    abstract fun appBackupDao(): AppBackupDao
}
