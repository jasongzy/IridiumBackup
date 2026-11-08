package com.xayah.core.model

import com.xayah.core.model.util.formatSize
import com.xayah.core.model.util.of
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitTest {
    @Test
    fun killAppOptionDefaultsToPauseAndHonorsSavedChoices() {
        assertEquals(KillAppOption.OPTION_III, KillAppOption.of(""))
        for (option in KillAppOption.entries) {
            assertEquals(option, KillAppOption.of(option.name))
        }
    }

    @Test
    fun testFormatSize() {
        assertEquals("999.00 Bytes", 999.0.formatSize())
        assertEquals("1.00 KB", 1_000.0.formatSize())
        assertEquals("1.00 MB", 1_000_000.0.formatSize())
        assertEquals("1.00 GB", 1_000_000_000.0.formatSize())
    }

    @Test
    fun testProtectedBackupNote() {
        val revision = revision("app.test", 0)

        assertTrue(revision.copy(note = "from Titanium Backup (PrOtEcTeD)").isProtectedByNote())
        assertTrue(revision.copy(note = "protected备份").isProtectedByNote())
        assertFalse(revision.copy(note = "unprotected backup").isProtectedByNote())
    }

    @Test
    fun retentionListsAllRevisionsAndSelectsOnlyOldUnprotectedOnes() {
        val newest = revision("app.one", 4)
        val protected = revision("app.one", 3, "protected")
        val old = revision("app.one", 2)
        val oldest = revision("app.one", 1)
        val only = revision("app.two", 1)
        val protectedOnly = revision("app.three", 1, "protected")
        val protectedNewest = revision("app.three", 2, "protected")

        assertEquals(
            listOf(
                RetentionRevision(newest, false),
                RetentionRevision(protected, false),
                RetentionRevision(old, true),
                RetentionRevision(oldest, true),
                RetentionRevision(protectedNewest, false),
                RetentionRevision(protectedOnly, false),
            ),
            findRetentionRevisions(
                listOf(oldest, only, protectedOnly, protected, newest, protectedNewest, old),
                retainCount = 1,
            ),
        )
    }

    private fun revision(packageName: String, createdAt: Long, note: String = "") = BackupRevisionEntity(
        packageName = packageName,
        userId = 0,
        createdAt = createdAt,
        appVersionName = "1.0",
        appVersionCode = 1,
        engine = BackupEngine.LEGACY,
        repositoryId = ":backup",
        artifactId = "$packageName@$createdAt",
        contentMask = 1,
        note = note,
    )
}
