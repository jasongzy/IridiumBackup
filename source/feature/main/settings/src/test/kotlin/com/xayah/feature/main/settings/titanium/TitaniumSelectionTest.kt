package com.xayah.feature.main.settings.titanium

import com.xayah.core.data.repository.TitaniumImportRepository.BackupCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class TitaniumSelectionTest {
    @Test
    fun searchSelectionKeepsHiddenVersionsAndSkipsIncompleteBackups() {
        val old = BackupCandidate("old", "/backup", "app.one", "Old name", "1", 1, 1, null, true, false, emptyList(), null)
        val current = old.copy(metadataPath = "new", label = "New name")
        val other = current.copy(metadataPath = "other", packageName = "app.two")
        val broken = current.copy(metadataPath = "broken", missingFiles = listOf("data.tar.gz"))
        val preview = TitaniumImportUiState(
            searchQuery = "new", backupCandidates = listOf(old, current, other, broken), selectedBackupIds = setOf(old.id),
        )
        val all = preview.selectBackups(true)
        assertEquals(true, preview.copy(backupCandidates = listOf(old, broken)).hasImportableCandidates)
        assertEquals(false, preview.copy(backupCandidates = listOf(broken)).hasImportableCandidates)
        assertEquals(setOf(old.id, current.id, other.id), all.selectedBackupIds)
        assertEquals(setOf(old.id, other.id), all.selectBackups(false, current.packageName).selectedBackupIds)
        assertEquals(setOf(old.id), all.selectBackups(false).selectedBackupIds)
    }
}
