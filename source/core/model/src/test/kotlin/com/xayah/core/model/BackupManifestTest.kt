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
    fun manifestEntriesMustBeSafeAndMatchTheDeclaredContents() {
        val manifest = BackupManifest(1, "com.example", 0, 1234, "1.0", 1, 1, files = null)
        val apk = BackupManifestFile("apk.tar.zst", 1, "a".repeat(64))
        val config = apk.copy(name = "package_restore_config.json")
        val valid = manifest.copy(files = listOf(apk, config))
        assertTrue(valid.hasValidFileEntries())
        val withDeviceData = valid.copy(files = listOf(config, apk, apk.copy(name = "user_de.tar.zst")))
        assertTrue(withDeviceData.copy(contentMask = 5).hasValidFileEntries())
        assertFalse(withDeviceData.hasValidFileEntries())
        assertFalse(manifest.hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(config, apk.copy(name = "../apk.tar.zst"))).hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(config, apk.copy(name = "apk.unknown"))).hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(config, apk, apk.copy(name = "apk.tar"))).hasValidFileEntries())
        assertFalse(valid.copy(contentMask = 0, files = listOf(config)).hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(apk)).hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(apk, config, config)).hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(apk.copy(sizeBytes = -1), config)).hasValidFileEntries())
        assertFalse(valid.copy(files = listOf(apk.copy(sha256 = ""), config)).hasValidFileEntries())
        val json = Gson().toJson(valid)
        for (malformed in listOf(json.replace("[{", "[null,{"), json.replaceFirst("\"${apk.sha256}\"", "null"))) {
            assertFalse(Gson().fromJson(malformed, BackupManifest::class.java).hasValidFileEntries())
        }
    }
}
