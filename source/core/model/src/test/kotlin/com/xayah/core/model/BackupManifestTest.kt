package com.xayah.core.model

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {
    @Test
    fun editableNotesDoNotChangeRevisionIdentity() {
        val manifest = BackupManifest(1, "com.example", 0, 1234, "1.0", 1, 1, note = "edited", files = emptyList())
        val revision = BackupRevisionEntity(
            packageName = "com.example", userId = 0, createdAt = 1234, appVersionName = "1.0", appVersionCode = 1,
            engine = BackupEngine.LEGACY, repositoryId = ":/backup", artifactId = "com.example/user_0@1234", contentMask = 1,
            note = "original",
        )
        assertTrue(manifest.matchesRevision(revision))
        assertFalse(manifest.matchesRevision(revision.copy(appVersionCode = 2)))
        assertFalse(manifest.matchesRevision(revision.copy(contentMask = 3)))
    }

    @Test
    fun manifestEntriesStayWithinTheRevisionDirectory() {
        val manifest = BackupManifest(1, "com.example", 0, 1234, "1.0", 1, 1, files = null)
        val valid = manifest.copy(files = listOf(BackupManifestFile("apk.tar.zst", 1, "digest")))
        assertTrue(valid.hasValidFileEntries())
        assertFalse(manifest.hasValidFileEntries())
        listOf("", ".", "..", "../../Download/file", "/absolute", "folder\\file").forEach { name ->
            assertFalse(manifest.copy(files = listOf(BackupManifestFile(name, 1, "digest"))).hasValidFileEntries())
        }
        val malformed = Gson().fromJson(Gson().toJson(valid).replace("[{", "[null,{"), BackupManifest::class.java)
        assertFalse(malformed.hasValidFileEntries())
    }
}
