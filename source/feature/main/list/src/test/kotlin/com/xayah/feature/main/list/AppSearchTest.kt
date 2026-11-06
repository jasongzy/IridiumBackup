package com.xayah.feature.main.list

import com.xayah.core.model.App
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val item = AppListItem(
        app = App(
            id = 1,
            packageName = "com.example.utility",
            userId = 0,
            label = "SystemHelper",
            versionName = "1.0",
            versionCode = 1,
            preserveId = 0,
            isSystemApp = false,
            isUpdatedSystemApp = false,
            isXposedModule = false,
            isFrozen = false,
            isInstalled = true,
            firstInstallTime = 0,
            lastUpdateTime = 0,
            lastBackupTime = 0,
            dataSizeBytes = 0,
            selectionFlag = 0,
            selected = false,
        ),
        revisionCount = 1,
        latestRevisionAt = null,
        hasApkBackup = true,
        hasDataBackup = true,
        hasMatchingApkBackup = true,
        labels = emptyList(),
        notes = "日常工具\nfrom Titanium Backup (protected)",
    )

    @Test
    fun matchesEveryTermInAnyOrderIgnoringCase() {
        assertTrue(appSearchPredicate("system helper")(item))
        assertTrue(appSearchPredicate("HELPER SyStEm")(item))
        assertFalse(appSearchPredicate("system missing")(item))
    }

    @Test
    fun matchesTermsAcrossNamesAndNotesAsLiteralText() {
        assertTrue(appSearchPredicate("helper com.example 日常 protected")(item))
        assertTrue(appSearchPredicate("(protected)")(item))
        assertFalse(appSearchPredicate("com.*")(item))
    }

    @Test
    fun handlesRepeatedAndUnicodeWhitespace() {
        assertTrue(appSearchPredicate("  system\t\nhelper\u3000protected\u00A0")(item))
        assertTrue(appSearchPredicate("")(item))
        assertTrue(appSearchPredicate(" \t\n\u3000\u00A0")(item))
    }
}
