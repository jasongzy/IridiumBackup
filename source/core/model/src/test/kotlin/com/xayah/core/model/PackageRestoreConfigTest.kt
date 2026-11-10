package com.xayah.core.model

import com.google.gson.Gson
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageRestoreConfigTest {
    @Test
    fun serializedConfigContainsOnlyBackupMetadata() {
        val json = Gson().toJson(config())

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertFalse(json.contains("firstInstallTime"))
        assertFalse(json.contains("lastUpdateTime"))
        assertFalse(json.contains("backupDir"))
        assertFalse(json.contains("\"id\""))
        assertFalse(json.contains("\"uid\""))
        assertFalse(json.contains("activated"))
        assertFalse(json.contains("blocked"))
        assertFalse(json.contains("enabled"))
        assertFalse(json.contains("storageStats"))
    }

    @Test
    fun androidSystemBackupsDoNotPropagateTheUserSeed() {
        val oldConfig = config().copy(packageName = "android", ssaid = "test-seed")
        val app = oldConfig.toPackageEntity("", "/backup")
        assertEquals("", app.extraInfo.ssaid)
        app.extraInfo.ssaid = oldConfig.ssaid
        assertEquals("", app.toRestoreConfig().ssaid)
    }

    @Test
    fun restoringTwoProfilesOnlyConflictsWhenTheyShareADestination() {
        val owner = config().toPackageEntity("", "/backup")
        val profile = config().copy(userId = 10).toPackageEntity("", "/backup")
        val request = PackageTaskRequest.Restore(listOf(owner, profile), sizeBytes = 0)

        assertNull(request.conflictingTarget())
        assertEquals(AppKey(owner.packageName, 0), request.copy(userId = 0).conflictingTarget())
    }

    private fun config() = PackageRestoreConfig(
        packageName = "com.example.app",
        userId = 0,
        createdAt = 1234,
        compressionType = CompressionType.ZSTD,
        label = "Example",
        versionName = "1.0.0",
        versionCode = 1,
        isSystemApp = false,
        hasKeystore = false,
        permissions = emptyList(),
        ssaid = "",
        dataStates = PackageDataStates(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(),
    )
}
