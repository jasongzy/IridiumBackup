package com.xayah.core.data.repository

import com.xayah.core.model.BackupManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitaniumCandidateTest {
    @Test
    fun aTimestampCollisionIsNotAnImportedBackup() {
        val candidate = TitaniumImportRepository.BackupCandidate(
            "backup.properties", "/backup", "com.example", "Example", "1.0", 12, 1234, "A".repeat(32),
            hasData = true, imported = false, missingFiles = emptyList(), iconPath = null,
        )
        val existing = BackupManifest(
            packageName = candidate.packageName, userId = 0, createdAt = candidate.createdAt,
            versionName = "1.0", versionCode = 12, contentMask = 3, files = emptyList(),
            note = "my edited note", titaniumSource = candidate.source,
        )
        assertTrue(candidate.matches(existing))
        assertFalse(candidate.matches(existing.copy(versionName = "1.1")))
        assertFalse(candidate.matches(existing.copy(versionCode = 13)))
        assertFalse(candidate.copy(apkMd5 = "b".repeat(32)).matches(existing))
        assertFalse(candidate.copy(hasData = false).matches(existing.copy(contentMask = 1)))
        assertFalse(candidate.matches(existing.copy(titaniumSource = null)))
        assertTrue(candidate.matches(existing.copy(titaniumSource = null, note = "from Titanium Backup (protected)\nkeep")))
    }
}
