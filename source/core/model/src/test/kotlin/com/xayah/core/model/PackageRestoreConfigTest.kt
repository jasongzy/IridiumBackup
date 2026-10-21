package com.xayah.core.model

import com.google.gson.Gson
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
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
    fun apkReuseRequiresMatchingVersionsAndContent() {
        val previous = config().toPackageEntity("", "/backup")
        assertTrue(previous.canReuseApkFrom(previous))
        assertFalse(previous.copy(packageInfo = previous.packageInfo.copy(versionName = "other")).canReuseApkFrom(previous))
        assertFalse(previous.copy(packageInfo = previous.packageInfo.copy(versionCode = 2)).canReuseApkFrom(previous))
        assertFalse(previous.copy(indexInfo = previous.indexInfo.copy(compressionType = CompressionType.TAR)).canReuseApkFrom(previous))
        assertFalse(previous.copy(dataStates = previous.dataStates.copy(apkState = DataState.NotSelected)).canReuseApkFrom(previous))
        assertFalse(previous.canReuseApkFrom(previous.copy(dataStates = previous.dataStates.copy(apkState = DataState.NotSelected))))
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
